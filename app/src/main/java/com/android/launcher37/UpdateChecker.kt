package com.android.launcher37

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * GitHub Releases 自动更新客户端。
 *
 * 数据源：`https://api.github.com/repos/<owner>/<repo>/releases/latest`
 * - `tag_name`         形如 `v20260829`（去掉 `v` 前缀为 versionName，YYYYMMDD 北京时）
 * - `name`             release 标题
 * - `body`             Markdown release notes
 * - `assets[0].browser_download_url`  APK 下载地址
 *
 * 对比策略：`versionName` 字典序 = 日历序（YYYYMMDD）；远端 > 本地 时升级。
 * 跳过前置版本/草稿（`prerelease=true` 或 `draft=true` 不下载）。
 *
 * 异步模型：检查 / 下载 / 安装调度到单线程 Executor 异步执行；
 * UI 反馈走 [mHandler] 切回主线程；`startActivity` 必须在主线程（Android 规范）。
 *
 * 时间：所有时间戳用 [System.currentTimeMillis]（epoch ms，UTC，时区无关）；
 * 24h 节流用 ms 差值；versionName 用北京时间（Asia/Shanghai）日期。
 *
 * 安装：下载完成后写 `cacheDir/update.apk`，发 `ACTION_INSTALL_PACKAGE`。
 * 需 `REQUEST_INSTALL_PACKAGES` 权限 + 自实现 `UpdateFileProvider`（manifest 已声明）。
 */
class UpdateChecker(
    private val mContext: Context,
    private val mListener: Listener?
) {
    interface Listener {
        fun onUpdateStart()
        fun onUpdateFound(info: UpdateInfo)
        fun onUpToDate()
        fun onProgress(percent: Int)
        fun onError(message: String)
    }

    /**
     * Release 元信息。
     *
     * @param tag         GitHub tag（如 `v1.0.1`）
     * @param versionName 去掉 `v` 前缀的版本号（如 `1.0.1`）
     * @param apkUrl      APK asset 下载地址
     * @param notes       release notes（Markdown）
     * @param sizeBytes   APK 文件字节数
     */
    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val sizeBytes: Long
    )

    companion object {
        private const val TAG = "Updater"
        const val GITHUB_API_LATEST =
            "https://api.github.com/repos/txq1996/CarLauncher/releases/latest"

        private const val TIMEOUT_MS = 15_000
        private const val UPDATE_FILE = "update.apk"
        private const val UA = "CarLauncher-Updater/1.0"

        /** 24h 节流：自动检查距上次成功检查不到 24h 直接跳过 */
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** SP key：上次成功自动检查的 epoch 毫秒 */
        private const val PREF_LAST_AUTO_CHECK = "update_last_auto_check"

        /** 系统 package installer 的几个包名（不同厂商不同） */
        private val INSTALLER_PACKAGES = arrayOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.huawei.systemmanager",
            "com.samsung.android.packageinstaller",
            "com.coloros.packageinstaller"
        )
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private var mDownloading = false

    /**
     * 单线程 Executor 跑异步检查/下载；同一 executor 复用线程，
     * 任务按提交顺序串行执行（避免多次点击/重启并发请求）。
     */
    private val mExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UpdateChecker").apply { isDaemon = true }
    }

    /**
     * 自动检查入口（启动时调用）。距上次成功自动检查 < 24h 直接跳过并回调 `onSkipped`；
     * 否则走与手动检查相同的检查/下载/安装链路。
     */
    fun checkOnLaunch() {
        if (mDownloading) return
        val now = System.currentTimeMillis()
        val last = mContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getLong(PREF_LAST_AUTO_CHECK, 0L)
        if (last > 0 && now - last < AUTO_CHECK_INTERVAL_MS) {
            // 24h 内已检查过；静默跳过，不打扰用户
            return
        }
        doCheckAndInstall(
            onSkip = { /* 节流命中，已在 doCheckAndInstall 入口处理 */ },
            isAuto = true
        )
    }

    /**
     * 手动检查入口（设置页"检查更新"按钮）。不受 24h 限制，立即跑。
     */
    fun checkManually() {
        if (mDownloading) {
            toast("已在更新中，请稍候")
            return
        }
        doCheckAndInstall(onSkip = {}, isAuto = false)
    }

    /** 兼容旧 API：等价于 [checkManually] */
    fun checkAndInstall() = checkManually()

    private fun doCheckAndInstall(onSkip: () -> Unit, isAuto: Boolean) {
        Log.i(TAG, "[Updater] doCheckAndInstall isAuto=$isAuto mDownloading=$mDownloading")
        if (!isOnline()) {
            Log.w(TAG, "[Updater] no network, skip")
            if (mListener != null) mListener.onError("当前无网络连接")
            else toast("当前无网络连接")
            return
        }
        mListener?.onUpdateStart()
        // 异步：检查 + 下载 + 装机调度全在单线程 Executor；UI 反馈走 mHandler 回主线程
        mExecutor.execute {
            try {
                queryAndInstall(markAutoChecked = isAuto)
            } catch (e: Throwable) {
                Log.e(TAG, "[Updater] failed in worker", e)
                postError("检查更新失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun queryAndInstall(markAutoChecked: Boolean) {
        try {
            val info = fetchLatestRelease() ?: run {
                Log.w(TAG, "[Updater] fetchLatestRelease returned null")
                if (markAutoChecked) writeLastAutoCheck()
                postError("未找到最新 release")
                return
            }
            // 成功完成一次与远端的对话；记录"上次自动检查"以触发 24h 节流
            if (markAutoChecked) writeLastAutoCheck()
            val current = currentVersionName()
            val cmp = compareVersion(info.versionName, current)
            Log.i(TAG, "[Updater] compare: local=$current remote=${info.versionName} cmp=$cmp")
            if (cmp <= 0) {
                postUpToDate()
                return
            }
            Log.i(TAG, "[Updater] new version found: ${info.versionName} (size=${info.sizeBytes}B)")
            postFound(info)
            downloadAndInstall(info)
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] queryAndInstall exception", e)
            postError("检查更新失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        Log.i(TAG, "[Updater] fetch GET $GITHUB_API_LATEST")
        val conn = (URL(GITHUB_API_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "[Updater] API non-2xx: $code")
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                Log.i(TAG, "[Updater] release is draft/prerelease, skip")
                return null
            }
            val tag = json.optString("tag_name")
            // versionName 解析：优先 tag_name（vYYYYMMDD.HHMMSS）去掉 v 前缀；
            // 如果 tag 是 "latest"（GitHub 单一 release tag）则回退到 release name（"CarLauncher <version>"）
            // 解析出形如 YYYYMMDD 或 YYYYMMDD.HHMMSS 的子串。
            val version: String = parseVersionName(tag, json.optString("name"))
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var sizeBytes: Long = 0
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    sizeBytes = a.optLong("size", 0)
                    break
                }
            }
            val url = apkUrl ?: run {
                Log.w(TAG, "[Updater] no .apk asset in release")
                return null
            }
            Log.i(TAG, "[Updater] fetched tag=$tag version=$version apkUrl=${url.takeLast(40)}")
            return UpdateInfo(
                tag = tag,
                versionName = version,
                apkUrl = url,
                notes = json.optString("body"),
                sizeBytes = sizeBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] fetchLatestRelease exception", e)
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        mDownloading = true
        val apk = File(mContext.cacheDir, UPDATE_FILE)
        if (apk.exists()) apk.delete()
        Log.i(TAG, "[Updater] download start: ${info.apkUrl.takeLast(40)} (expected=${info.sizeBytes}B)")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "[Updater] download non-2xx: $code")
                postError("下载失败：HTTP $code")
                return
            }
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.sizeBytes
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    FileOutputStream(apk).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastReported = -1
                        while (bis.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    mHandler.post { mListener?.onProgress(pct) }
                                }
                            }
                        }
                        out.flush()
                    }
                }
            }
            Log.i(TAG, "[Updater] download done: ${apk.absolutePath} size=${apk.length()}B")
            mHandler.post { installApk(apk) }
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] download exception", e)
            postError("下载失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
            mDownloading = false
        }
    }

    /**
     * 从 release 元数据解析 versionName。
     *
     * 优先：tag_name 去掉 v 前缀（如 v20260829.044047 → 20260829.044047）
     * 回退：当 tag_name 是 "latest"（GitHub 单一 release alias），
     *       从 release `name`（"CarLauncher 20260829.044047"）提取 YYYYMMDD[.HHMMSS]
     *       数字串。
     * 最终兜底：tag_name 原串。
     */
    private fun parseVersionName(tagName: String, releaseName: String): String {
        val fromTag = tagName.removePrefix("v")
        if (fromTag.isNotEmpty() && fromTag != "latest") return fromTag
        val m = Regex("""(\d{8}(?:\.\d+)?)""").find(releaseName)
        if (m != null) return m.groupValues[1]
        return tagName
    }

    private fun installApk(apk: File) {
        if (!apk.exists() || apk.length() == 0L) {
            Log.w(TAG, "[Updater] installApk invalid file: ${apk.absolutePath} exists=${apk.exists()} size=${apk.length()}")
            postError("下载文件无效")
            return
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android N+：file:// URI 在 Intent 中跨应用传递会触发
            // FileUriExposedException；改用 manifest 声明的 UpdateFileProvider
            // 把 cacheDir/update.apk 包成 content:// URI 给系统安装器读取。
            val authority = "${mContext.packageName}.updater"
            val rel = "update_apk/${apk.name}"
            Uri.parse("content://$authority/$rel")
        } else {
            Uri.fromFile(apk)
        }
        Log.i(TAG, "[Updater] installApk uri=$uri (file=${apk.absolutePath} size=${apk.length()})")

        // 显式 grant 给可能接管 ACTION_INSTALL_PACKAGE 的几个系统 package installer。
        // addFlags(FLAG_GRANT_READ_URI_PERMISSION) 在 system uid 启动的 activity 时
        // 不一定自动传播给被 startActivity 的目标；这里 fallback 显式 grant。
        runCatching {
            for (pkg in INSTALLER_PACKAGES) {
                mContext.grantUriPermission(
                    pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }.onFailure { Log.w(TAG, "[Updater] grantUriPermission best-effort failed", it) }

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            mContext.startActivity(intent)
            Log.i(TAG, "[Updater] startActivity(ACTION_INSTALL_PACKAGE) ok")
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] startActivity failed", e)
            postError("无法启动安装器：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun isOnline(): Boolean {
        val cm = mContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun currentVersionName(): String = try {
        mContext.packageManager.getPackageInfo(mContext.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** 版本号字典序比较：a<b 返回负，相等返回 0，a>b 返回正 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da - db
        }
        return 0
    }

    private fun postError(msg: String) = mHandler.post {
        if (mListener != null) mListener.onError(msg) else toast(msg)
    }
    private fun postUpToDate() = mHandler.post {
        if (mListener != null) mListener.onUpToDate() else toast("已是最新版本")
    }
    private fun postFound(info: UpdateInfo) = mHandler.post {
        mListener?.onUpdateFound(info)
    }

    /** 记录本次自动检查的 epoch 毫秒（用于 24h 节流） */
    private fun writeLastAutoCheck() {
        mContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_AUTO_CHECK, System.currentTimeMillis())
            .apply()
    }
    private fun toast(s: String) {
        if (mContext is Activity) {
            Toast.makeText(mContext, s, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(mContext.applicationContext, s, Toast.LENGTH_SHORT).show()
        }
    }
}
