package com.android.launcher37

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Releases 自动更新客户端。
 *
 * 数据源：`https://api.github.com/repos/<owner>/<repo>/releases/latest`
 * - `tag_name`         形如 `v20260829`（去掉 `v` 前缀为 versionName，YYYYMMDD 北京时）
 * - `name`             release 标题
 * - `body`             Markdown release notes（含 `- versionCode: \`NNNN\``）
 * - `assets[0].browser_download_url`  APK 下载地址
 *
 * 对比策略：**versionCode**（整数 = 构建 epoch 秒数，**单调递增**），
 * 远端 > 本地 时升级。回退：当 release body 解析不出 versionCode，
 * 改用 versionName 字典序（YYYYMMDD），确保鲁棒性。
 * 跳过前置版本/草稿（`prerelease=true` 或 `draft=true` 不下载）。
 *
 * 异步模型：检查 / 下载 / 安装调度到 [SharedExecutor.io()]（2 线程 fixed pool，
 * 符合 AGENTS #5 "统一用 SharedExecutor.io()，禁止自建 Executor"）异步执行；
 * UI 反馈走 [MainThread.handler] 切回主线程；`startActivity` 必须在主线程（Android 规范）。
 *
 * 时间：所有时间戳用 [System.currentTimeMillis]（epoch ms，UTC，时区无关）；
 * 24h 节流用 ms 差值；versionName 用北京时间（Asia/Shanghai）日期。
 *
 * 安装：下载完成后写 `cacheDir/update.apk`，调 [PackageInstaller] session API 装。
 * 需 `REQUEST_INSTALL_PACKAGES` 权限。**不用** `Intent.ACTION_INSTALL_PACKAGE +
 * content URI + grant flag` — 该路径在 `sharedUserId="android.uid.system"` 进程下
 * 被 framework 端 `UriGrantsManagerService` 拒绝 grant（"For security reasons,
 * the system cannot issue a Uri permission grant"），目标 installer 读 URI 时
 * 报 `UID xxx does not have permission` 并 crash。PackageInstaller session 走
 * system_server 内部路径，不依赖 URI grant。self-update 时 framework 走
 * `installPackageLI` 静默路径（不弹 dialog）；非 self-update 场景会弹系统确认框。
 *
 * 确认流程：检查发现新版本后**不自动下载**，仅回调 [Listener.onUpdateFound]
 * 并挂起待确认（[mPending]）；调用方弹窗让用户确认后调 [confirmUpdate]
 * 才开始下载安装。
 */
class UpdateChecker(
    private val mApp: Application,
    private val mListener: Listener?
) {
    /**
     * 兼容旧调用：允许 [Context]（自动取 applicationContext）+ null listener。
     * 主入口见 [mApp] 字段；构造时如果传入的是 Activity，会保留为 [mActivity]（用于回调），
     * Activity 销毁时通过 [release] 解引用，避免 Toast/BadTokenException。
     */
    constructor(context: Context, listener: Listener?) : this(
        context.applicationContext as Application, listener
    ) {
        if (context is Activity) mActivity = context
    }

    /** 回调用的 Activity 引用；[release] 显式清掉（避免 Activity dead 后回调进死 Activity） */
    private var mActivity: Activity? = null
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
     * @param versionCode 远端构建的整数 versionCode（构建 epoch 秒数）。
     *                    若 release body 解析不出，落到 0L（fallback 用 versionName）。
     * @param apkUrl      APK asset 下载地址
     * @param notes       release notes（Markdown）
     * @param sizeBytes   APK 文件字节数
     */
    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val versionCode: Long,
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

        /**
         * PackageInstaller.commit() 的 PendingIntent 回调 action — 安装完成后
         * system 广播到我们注册的 [mInstallResultReceiver]。包内私有。
         */
        const val ACTION_INSTALL_COMMIT_RESULT =
            "com.android.launcher37.action.INSTALL_COMMIT_RESULT"
    }

    private val mHandler = MainThread.handler
    private val mDownloading = AtomicBoolean(false)
    private val mCancelled = AtomicBoolean(false)

    /** 已发现待用户确认的更新；确认后由 [confirmUpdate] 消费并清空 */
    @Volatile private var mPending: UpdateInfo? = null

    /**
     * 异步任务走 [SharedExecutor]（AGENTS #5 不允许 newSingleThreadExecutor）。
     * 单跑约束用 [mDownloading] 守护（双重提交会被 `if (mDownloading.get()) return` 拦截）。
     */
    private val mExecutor = SharedExecutor.io()

    /**
     * 自动检查入口（启动时调用）。距上次成功自动检查 < 24h 直接静默跳过（无回调）；
     * 否则走与手动检查相同的检查/下载/安装链路。
     */
    fun checkOnLaunch() {
        if (mDownloading.get()) return
        val now = System.currentTimeMillis()
        val last = mApp.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getLong(PREF_LAST_AUTO_CHECK, 0L)
        if (last > 0 && now - last < AUTO_CHECK_INTERVAL_MS) {
            // 24h 内已检查过；静默跳过，不打扰用户
            return
        }
        doCheckAndInstall(isAuto = true)
    }

    /**
     * 手动检查入口（设置页"检查更新"按钮）。不受 24h 限制，立即跑。
     */
    fun checkManually() {
        if (mDownloading.get()) {
            toast("已在更新中，请稍候")
            return
        }
        doCheckAndInstall(isAuto = false)
    }

    /**
     * 用户在确认弹窗点"立即更新"后调用：开始下载并安装 [mPending] 的更新。
     * 无待确认更新或已在下载中时静默忽略。
     */
    fun confirmUpdate() {
        val info = mPending ?: return
        if (mDownloading.get()) return
        mExecutor.execute {
            try {
                downloadAndInstall(info)
            } catch (e: Throwable) {
                Log.e(TAG, "[Updater] download failed in confirmUpdate", e)
                postError("下载更新失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 释放：清 Activity 引用 + 取消正在进行的下载。
     * 由 caller 的 [android.app.Activity.onDestroy] 调用，避免 Toast 进死 Activity。
     */
    fun release() {
        mActivity = null
        mCancelled.set(true)
    }

    private fun doCheckAndInstall(isAuto: Boolean) {
        Log.i(TAG, "[Updater] doCheckAndInstall isAuto=$isAuto mDownloading=${mDownloading.get()}")
        if (!isOnline()) {
            Log.w(TAG, "[Updater] no network, skip")
            if (mListener != null) mListener.onError("当前无网络连接")
            else toast("当前无网络连接")
            return
        }
        mListener?.onUpdateStart()
        mCancelled.set(false)
        // 异步：检查 + 下载 + 装机调度全在 SharedExecutor 任务；UI 反馈走 mHandler 回主线程
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
            val localCode = currentVersionCode()
            val localName = currentVersionName()
            val (cmp, basis) = if (info.versionCode > 0L) {
                val c = info.versionCode.compareTo(localCode)
                Log.i(TAG, "[Updater] compare: localCode=$localCode remoteCode=${info.versionCode} cmp=$c (versionCode)")
                c to "versionCode"
            } else {
                val c = UpdateVersion.compareVersionName(info.versionName, localName)
                Log.w(TAG, "[Updater] remote versionCode=0, fallback to versionName: local=$localName remote=${info.versionName} cmp=$c")
                c to "versionName"
            }
            if (cmp <= 0) {
                Log.i(TAG, "[Updater] up-to-date by $basis")
                postUpToDate()
                return
            }
            Log.i(TAG, "[Updater] new version found by $basis: name=${info.versionName} code=${info.versionCode} (size=${info.sizeBytes}B)")
            // 挂起待确认：由调用方弹窗，用户确认后调 [confirmUpdate] 才开始下载安装
            mPending = info
            postFound(info)
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] queryAndInstall exception", e)
            postError("检查更新失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        Log.i(TAG, "[Updater] fetch GET $GITHUB_API_LATEST")
        val conn = openHttp(GITHUB_API_LATEST, accept = "application/vnd.github+json")
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
            val version: String = UpdateVersion.parseVersionName(tag, json.optString("name"))
            val body = json.optString("body")
            val versionCode: Long = UpdateVersion.parseVersionCodeFromBody(body)
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
            Log.i(TAG, "[Updater] fetched tag=$tag version=$version versionCode=$versionCode apkUrl=${url.takeLast(40)}")
            return UpdateInfo(
                tag = tag,
                versionName = version,
                versionCode = versionCode,
                apkUrl = url,
                notes = body,
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
        mPending = null
        mDownloading.set(true)
        val apk = File(mApp.cacheDir, UPDATE_FILE)
        if (apk.exists()) apk.delete()
        Log.i(TAG, "[Updater] download start: ${info.apkUrl.takeLast(40)} (expected=${info.sizeBytes}B)")
        val conn = openHttp(info.apkUrl)
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
                            if (mCancelled.get()) {
                                Log.i(TAG, "[Updater] download cancelled by release()")
                                return
                            }
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
            if (mCancelled.get()) {
                Log.i(TAG, "[Updater] download cancelled before install")
                return
            }
            Log.i(TAG, "[Updater] download done: ${apk.absolutePath} size=${apk.length()}B")
            mHandler.post {
                if (!apk.exists() || apk.length() == 0L) {
                    postError("下载文件无效"); return@post
                }
                installViaPackageInstaller(apk)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] download exception", e)
            postError("下载失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
            mDownloading.set(false)
        }
    }

    private fun currentVersionName(): String = try {
        mApp.packageManager.getPackageInfo(mApp.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * 当前安装包的 versionCode（构建 epoch 秒数；失败兜底 0L）。
     * SP `test_fake_local_code` 字段可被外部写一个伪造值（>= 0）以让本地比远端
     * "看起来更老"，强制走下载路径（用于开发 / 测试）。
     */
    private fun currentVersionCode(): Long {
        val fake = mApp.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getLong("test_fake_local_code", -1L)
        if (fake >= 0L) {
            Log.i(TAG, "[Updater] currentVersionCode overridden by SP: $fake")
            return fake
        }
        return try {
            val pi = mApp.packageManager.getPackageInfo(mApp.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 用 PackageInstaller session API 安装 APK。
     * 不用 Intent.ACTION_INSTALL_PACKAGE + content URI：在 sharedUserId=system 下
     * framework 拒绝 URI grant，installer 读 URI 时 SecurityException crash。
     * PackageInstaller session 走 system_server 内部路径，不需要 URI grant。
     * self-update 时 framework 走静默路径（不弹 dialog）；非 self-update 会弹确认框。
     * 用 MODE_FULL_INSTALL：INHERIT_EXISTING 要求新旧 versionCode 相同，不适用于升级。
     */
    private fun installViaPackageInstaller(apk: File) {
        val pm = mApp.packageManager
        val pi = pm.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(mApp.packageName)
            setSize(apk.length())
        }

        // 注册结果 broadcast receiver（一次性）。在 self-update 场景下 commit 后
        // caller 进程会被 framework 杀掉，结果可能收不到；但在非 self-update 场景
        // 这个 receiver 能拿到安装结果。
        val filter = IntentFilter(ACTION_INSTALL_COMMIT_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mApp.registerReceiver(
                mInstallResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            mApp.registerReceiver(mInstallResultReceiver, filter)
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        try {
            sessionId = pi.createSession(params)
            Log.i(TAG, "[Updater] PackageInstaller session created: id=$sessionId size=${apk.length()}")
            session = pi.openSession(sessionId)

            // Stream APK 进 session。必须用 FileInputStream 读本机文件路径（session
            // 在 system_server 进程里 openFile 走的是 caller 自己的权限）。
            session.openWrite("package", 0, apk.length()).use { out ->
                FileInputStream(apk).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }

            // 构造 commit PendingIntent：安装完成后 system 回调这个 intent 通知结果
            val statusBroadcast = Intent(ACTION_INSTALL_COMMIT_RESULT).apply {
                setPackage(mApp.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi2 = PendingIntent.getBroadcast(
                mApp, sessionId, statusBroadcast, flags
            )

            session.commit(pi2.intentSender)
            Log.i(TAG, "[Updater] PackageInstaller session committed (id=$sessionId) → 系统安装流程启动")
        } catch (e: Exception) {
            Log.e(TAG, "[Updater] PackageInstaller failed", e)
            if (sessionId >= 0) {
                runCatching { pi.abandonSession(sessionId) }
            }
            runCatching { mApp.unregisterReceiver(mInstallResultReceiver) }
            postError("无法启动安装器：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * PackageInstaller commit 完成后 system 发的结果 broadcast。一次性 receiver：
     * 收到 STATUS_SUCCESS 时提示用户；self-update 场景下 caller 进程在 commit 后
     * 被 framework 杀掉，这里可能根本收不到（属于正常情况，不是 bug）。
     */
    private val mInstallResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) ?: return
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "[Updater] install SUCCESS")
                    toast("安装成功")
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    Log.i(TAG, "[Updater] install PENDING_USER_ACTION（framework 已启动确认 UI）")
                }
                else -> {
                    Log.w(TAG, "[Updater] install FAILED status=$status msg=$msg")
                    toast("安装失败：$msg")
                }
            }
            runCatching { mApp.unregisterReceiver(this) }
        }
    }

    /** 统一 HTTP 连接初始化：UA + 超时 + 可选 Accept header */
    private fun openHttp(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            if (accept != null) setRequestProperty("Accept", accept)
        }

    private fun isOnline(): Boolean {
        val cm = mApp.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
        mApp.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_AUTO_CHECK, System.currentTimeMillis())
            .apply()
    }
    private fun toast(s: String) {
        val ctx = mActivity ?: mApp
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }
}
