package com.android.launcher37

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 底栏 V2Button 持久化 + PackageManager 工具 + 启动动作（app / split）。
 *
 * V2Button 类型与字段语义：
 * - `map` / `home` / `company` / `stop` （action 子类型）
 * - `app` + `pkg/cls` 标识
 * - `split` + 左右两侧 `pkg/cls`
 * - `clean`（无参数）
 * - `settings`（无参数）
 *
 * 持久化格式：JSON 数组，SP key `custom_buttons`，文件名 `launcher37_config`。
 * 不可用时（`app` 未安装 / `split` 任一侧未安装）启动时自动剔除并回写。
 */
object Store {

    /** 分屏比例（默认 65%） */
    private const val RATIO = 65

    /** 底栏自定义按钮 JSON 序列化 key */
    private const val KEY_CUSTOM_BUTTONS = "custom_buttons"

    /** 抽屉应用自定义顺序（逗号分隔 pkg/cls；pkg/cls 不含逗号，安全） */
    private const val KEY_APP_ORDER = "app_drawer_order"

    /** 抽屉隐藏应用（逗号分隔 pkg/cls 集合） */
    private const val KEY_APP_HIDDEN = "app_drawer_hidden"

    /** Label 缓存（256 条封顶） */
    private val LABEL_CACHE = LruCache<String, String>(256)

    /** Icon 缓存（256 条封顶） */
    private val ICON_CACHE = LruCache<String, Drawable>(256)

    /**
     * `v2Buttons` 缓存：按 Context 弱引用 key 缓存底栏按钮列表。
     * 同一 Activity 内 `Store.v2Buttons(this)` 多次调用（onCreate / refresh /
     * picker / 配置变更）只算一次主线程 binder；Activity 销毁后 WeakReference 自然 GC。
     *
     * 只缓存"已加载完成"的列表；首次调用走 [v2Buttons] 的完整加载路径。
     */
    private val V2BUTTONS_CACHE = java.util.WeakHashMap<Context, List<V2Button>>()

    /** 当前应用包名（用于应用查询时排除自身） */
    const val SELF_PKG = "com.android.launcher37"

    // ── 底栏按钮模型 ──────────────────────

    /**
     * 底栏自定义按钮定义。
     *
     * 5 种类型由 [type] 决定字段语义：
     * - `"map"`: 地图快捷指令，[action] 取值 `home`/`company`/`stop`
     * - `"app"`: 启动应用，[id] 形如 `com.x/.Main`
     * - `"split"`: 应用分屏，[left] / [right] 各为 `pkg/cls`
     * - `"clean"`: 内存清理，无参数
     * - `"settings"`: 打开桌面设置，无参数
     *
     * 不可变对象，所有字段通过工厂方法初始化（构造器私有）。判等用 [sameAs]。
     */
    data class V2Button(
        val type: String,
        val action: String = "",
        val id: String = "",
        val left: String = "",
        val right: String = ""
    ) {
        /** 地图快捷指令按钮。`action` 取 `home` / `company` / `stop`。 */
        companion object {
            @JvmStatic fun map(action: String) = V2Button(type = "map", action = action)

            /** 启动应用按钮。`id` 必须为 `pkg/cls` 形式。 */
            @JvmStatic fun app(id: String) = V2Button(type = "app", id = id)

            /**
             * 应用分屏按钮。左右两侧各为 `pkg/cls` 形式的应用标识，
             * 分屏方向由 [DockBar.launchSplit] 决定，`left` 始终在前。
             */
            @JvmStatic fun split(left: String, right: String) =
                V2Button(type = "split", left = left, right = right)

            /** 内存清理快捷按钮（无参数） */
            @JvmStatic fun clean() = V2Button(type = "clean")

            /** 桌面设置快捷按钮 */
            @JvmStatic fun settings() = V2Button(type = "settings")
        }

        /**
         * 业务相等（包含分屏左右顺序）。直接复用 data class 自带的 [equals]
         * ——它已经比对所有 5 个字段。保留此方法仅为 API 兼容（外层测试/历史代码
         * 仍可能调用 `b.sameAs(other)`）。新代码请用 `==`。
         */
        fun sameAs(o: V2Button): Boolean = this == o
    }

    // ── 底栏按钮持久化 ──────────────────────

    /**
     * 加载底栏按钮列表。无持久化时回填默认（map×2 + 优先用户 app）。
     * 加载时自动剔除失效项（app 未安装 / split 任一侧未安装）并回写。
     */
    @JvmStatic
    fun v2Buttons(c: Context): MutableList<V2Button> {
        V2BUTTONS_CACHE[c]?.let { return ArrayList(it) }
        val sp = prefs(c)
        val out = ArrayList<V2Button>()
        if (!sp.contains(KEY_CUSTOM_BUTTONS)) {
            out.add(V2Button.map("home"))
            out.add(V2Button.map("company"))
            val all: MutableList<android.content.pm.ResolveInfo> =
                AppQuery.launcherEntries(c, null).toMutableList()
            val systemFlag = android.content.pm.ApplicationInfo.FLAG_SYSTEM
            all.sortWith(Comparator<android.content.pm.ResolveInfo> { a, b ->
                val aUser = (a.activityInfo.applicationInfo.flags and systemFlag) == 0
                val bUser = (b.activityInfo.applicationInfo.flags and systemFlag) == 0
                if (aUser != bUser) return@Comparator if (aUser) -1 else 1
                0
            })
            for (ri in all) {
                if (out.size >= DockBar.MAX_DOCK_BUTTONS) break
                out.add(V2Button.app(AppQuery.appId(ri)))
            }
            saveV2Buttons(c, out)
            return out
        }
        try {
            val arr = JSONArray(sp.getString(KEY_CUSTOM_BUTTONS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("t")) {
                    "map" -> out.add(V2Button.map(o.optString("a")))
                    "app" -> out.add(V2Button.app(o.optString("id")))
                    "split" -> out.add(V2Button.split(o.optString("l"), o.optString("r")))
                    "clean" -> out.add(V2Button.clean())
                    "settings" -> out.add(V2Button.settings())
                }
            }
        } catch (e: Exception) {
            // 解析失败：忽略
        }
        // 移除失效项：app 未安装、split 任一侧未安装
        var changed = false
        val it = out.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.type == "app" && !installed(c, pkgOf(b.id))) {
                it.remove(); changed = true
            } else if (b.type == "split") {
                if (!installed(c, pkgOf(b.left)) || !installed(c, pkgOf(b.right))) {
                    it.remove(); changed = true
                }
            }
        }
        if (changed) saveV2Buttons(c, out)
        V2BUTTONS_CACHE[c] = out.toList()
        return out
    }

    @JvmStatic
    fun saveV2Buttons(c: Context, btns: List<V2Button>) {
        // 回写缓存：保存后下一次 v2Buttons 直接命中，避免重复走 SP/默认构建路径
        V2BUTTONS_CACHE[c] = btns.toList()
        val arr = JSONArray()
        try {
            for (b in btns) {
                val o = JSONObject()
                o.put("t", b.type)
                when (b.type) {
                    "map" -> o.put("a", b.action)
                    "app" -> o.put("id", b.id)
                    "split" -> { o.put("l", b.left); o.put("r", b.right) }
                }
                arr.put(o)
            }
        } catch (e: Exception) {
            // 序列化失败
        }
        prefs(c).edit().putString(KEY_CUSTOM_BUTTONS, arr.toString()).apply()
    }

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
     * 构造特定 Activity 的启动 Intent。`id` 格式 `pkg/cls`（同 [Store.V2Button.id]），
     * cls 为空时返回 null，让调用方走兜底路径（如 getLaunchIntentForPackage）。
     * 提升可见性以便外部模块（如 MusicDelegate）能复用。
     */
    fun entryIntent(c: Context, id: String): Intent? {
        val (pkg, cls) = splitId(id)
        if (cls.isEmpty()) return null
        return Intent().setClassName(pkg, cls)
    }

    /** 按应用标识启动 */
    @JvmStatic
    fun launchApp(c: Context, id: String) {
        // Android 9 兼容：若启动的是当前 PIP 地图且任务在 VD 上，优先搬移到主屏全屏
        try {
            val pkg = pkgOf(id)
            val app = c.applicationContext as? LauncherApp
            val pipPkg = app?.pipController?.resolvePkg()
            if (pipPkg != null && pipPkg == pkg) {
                if (app.pipController?.expandToFullscreen() == true) return
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
