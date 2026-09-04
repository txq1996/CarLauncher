package com.android.launcher37.data
import com.android.launcher37.LauncherApp
import com.android.launcher37.R
import com.android.launcher37.util.IconNormalizer
import com.android.launcher37.util.MainThread
import com.android.launcher37.util.Prefs
import com.android.launcher37.data.AppQuery
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.WindowManager

/**
 * PackageManager 工具 + 启动动作（app / split）+ 抽屉/标签持久化。
 *
 * 应用标识统一为 `pkg/cls` 形式（见 [AppQuery.appId]）；label/icon 走 LRU 缓存，
 * 图标经 [IconNormalizer] 归一化（日夜主题自适应底色）。
 */
object Store {

    /** 分屏比例（默认 65%） */
    private const val RATIO = 65

    /** 抽屉应用自定义顺序（逗号分隔 pkg/cls；pkg/cls 不含逗号，安全） */
    private const val KEY_APP_ORDER = "app_drawer_order"

    /** 抽屉隐藏应用（逗号分隔 pkg/cls 集合） */
    private const val KEY_APP_HIDDEN = "app_drawer_hidden"

    /** Label 缓存（256 条封顶） */
    private val LABEL_CACHE = LruCache<String, String>(256)

    /** Icon 缓存（256 条封顶） */
    private val ICON_CACHE = LruCache<String, Drawable>(256)

    /** 当前应用包名（用于应用查询时排除自身） */
    const val SELF_PKG = "com.android.launcher37"

    // ── 抽屉应用排序 / 隐藏持久化 ──────────────────────

    /** 抽屉应用自定义顺序；未设置时返回空列表 */
    @JvmStatic
    fun drawerOrder(c: Context): List<String> =
        prefs(c).getString(KEY_APP_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    @JvmStatic
    fun saveDrawerOrder(c: Context, ids: List<String>) {
        prefs(c).edit().putString(KEY_APP_ORDER, ids.joinToString(",")).apply()
    }

    /** 抽屉隐藏应用集合；未设置时返回空集 */
    @JvmStatic
    fun drawerHidden(c: Context): Set<String> =
        prefs(c).getString(KEY_APP_HIDDEN, null)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    @JvmStatic
    fun saveDrawerHidden(c: Context, ids: Set<String>) {
        prefs(c).edit().putString(KEY_APP_HIDDEN, ids.joinToString(",")).apply()
    }

    // ── PackageManager 工具 ──────────────────────

    /**
     * 从 `pkg/cls` 标识中提取包名。
     *
     * 多入口应用精确到组件时使用 `pkg/cls` 形式（见 `V2Button.id`），
     * 本方法只取 `/` 之前的部分。无 `/` 时原样返回。
     *
     * @param id 应用标识
     * @return 包名部分；无 `/` 时返回原串
     */
    @JvmStatic
    fun pkgOf(id: String): String {
        val slash = id.indexOf('/')
        return if (slash > 0) id.substring(0, slash) else id
    }

    /** 拆分 `pkg/cls` 为 (pkg, cls)；无 `/` 时 cls 为空串。 */
    private fun splitId(id: String): Pair<String, String> {
        val slash = id.indexOf('/')
        return if (slash > 0) id.substring(0, slash) to id.substring(slash + 1)
        else id to ""
    }

    @JvmStatic
    fun label(c: Context, id: String): String {
        LABEL_CACHE.get(id)?.let { return it }
        val pm = c.packageManager
        val (pkg, cls) = splitId(id)
        var result = pkg
        if (cls.isNotEmpty()) {
            try {
                val l = pm.getActivityInfo(ComponentName(pkg, cls), 0).loadLabel(pm)
                if (l.isNotEmpty()) result = l.toString()
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (result == pkg) {
            try {
                val l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
                if (l.isNotEmpty()) result = l.toString()
            } catch (e: Exception) {
                // 忽略
            }
        }
        LABEL_CACHE.put(id, result)
        return result
    }

    @JvmStatic
    fun icon(c: Context, id: String): Drawable? {
        ICON_CACHE.get(id)?.let { return it }
        val pm = c.packageManager
        val (pkg, cls) = splitId(id)
        var d: Drawable? = null
        if (cls.isNotEmpty()) {
            try {
                d = pm.getActivityInfo(ComponentName(pkg, cls), 0).loadIcon(pm)
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (d == null) {
            try {
                d = pm.getApplicationIcon(pkg)
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (d == null) return null
        ICON_CACHE.put(id, d)
        return d
    }

    @JvmStatic
    fun installed(c: Context, id: String): Boolean = try {
        c.packageManager.getPackageInfo(pkgOf(id), 0)
        true
    } catch (e: Exception) {
        false
    }

    @JvmStatic fun normalizedIcon(c: Context, id: String) =
        IconNormalizer.normalizedIcon(c, id)
    @JvmStatic fun normalizedGlyphIcon(c: Context, r: Int) = IconNormalizer.normalizedGlyphIcon(c, r)
    @JvmStatic fun normalizedSplitIcon(c: Context, l: String, r: String) =
        IconNormalizer.normalizedSplitIcon(c, l, r)
    @JvmStatic fun normalizedEmoji(c: Context, e: String) =
        IconNormalizer.normalizedEmoji(c, e)

    // ── 启动动作 ──────────────────────

    /**
     * 构造特定 Activity 的启动 Intent。`id` 格式 `pkg/cls`，
     * cls 为空时返回 null，让调用方走兜底路径（如 getLaunchIntentForPackage）。
     * 提升可见性以便外部模块（如歌词卡绑定启动）能复用。
     */
    fun entryIntent(c: Context, id: String): Intent? {
        val (pkg, cls) = splitId(id)
        if (cls.isEmpty()) return null
        return Intent().setClassName(pkg, cls)
    }

    /** 按应用标识启动 */
    @JvmStatic
    fun launchApp(c: Context, id: String) {
        // Android 9 兼容：若启动的是某个 VDWidget 承载的 App 且任务在 VD 上，
        // 优先搬移到主屏全屏
        try {
            val pkg = pkgOf(id)
            val app = c.applicationContext as? LauncherApp
            if (app?.activeHost?.vdBoundedPkgs()?.contains(pkg) == true) {
                if (app.activeHost?.expandVdToFullscreen(pkg) == true) return
            }
        } catch (_: Throwable) {}
        try {
            entryIntent(c, id)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(it)
            }
        } catch (e: Exception) {
            // 启动失败：忽略
        }
    }

    /**
     * 触发分屏：发送 `com.syu.splitscreenbutton` 广播（由 `com.syu.ms` 接收执行），
     * 2 秒后反射 `IActivityTaskManager.resizeDockedStack()` 调整左右比例。
     */
    @JvmStatic
    fun launchSplit(c: Context, leftId: String, rightId: String) {
        var sent = false
        try {
            val l = entryIntent(c, leftId)
            val r = entryIntent(c, rightId)
            if (l != null && r != null
                && l.component != null && r.component != null
            ) {
                val b = Intent("com.syu.splitscreenbutton")
                    .putExtra("firstpkg", l.component!!.packageName)
                    .putExtra("firstact", l.component!!.className)
                    .putExtra("secondpkg", r.component!!.packageName)
                    .putExtra("secondact", r.component!!.className)
                c.sendBroadcast(b)
                sent = true
            }
        } catch (e: Exception) {
            // 忽略
        }
        if (sent) {
            MainThread.handler.postDelayed({ resizeDocked(c) }, 2000)
        }
    }

    private fun resizeDocked(c: Context) {
        try {
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels
            val docked: Rect = if (w > h) {
                Rect(0, 0, w * RATIO / 100, h)
            } else {
                Rect(0, 0, w, h * RATIO / 100)
            }
            val atm = Class.forName("android.app.ActivityTaskManager")
            val service = atm.getMethod("getService").invoke(null)
            val resize = service!!.javaClass.getMethod(
                "resizeDockedStack",
                Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java
            )
            resize.invoke(service, docked, null, null, null, null)
        } catch (e: ReflectiveOperationException) {
            // 反射 API 在新 Android 版本被改签名/隐藏；记录一次让维护者知道
            android.util.Log.w("Store", "resizeDockedStack reflection failed (API change?)", e)
        } catch (e: SecurityException) {
            android.util.Log.w("Store", "resizeDockedStack denied (uid=${android.os.Process.myUid()})", e)
        } catch (e: RuntimeException) {
            android.util.Log.w("Store", "resizeDockedStack failed", e)
        }
    }

    private fun prefs(c: Context): SharedPreferences = Prefs.of(c)
}
