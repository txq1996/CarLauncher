package com.android.launcher37.home.widget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 布局持久化：命名布局仓库（内部存储 filesDir/layouts.json）。
 *
 * JSON 结构：
 * { "version":1, "active":"默认布局",
 *   "layouts":[ {"name":"默认布局","pages":[ <HomeLayout JSON>... ]} ] }
 *
 * 每个 HomeLayout（=一页）结构：
 * { "version":1, "screenWidth":1280, "screenHeight":720,
 *   "widgets":[ {"id":1,"type":"time","x":..,"y":..,"w":..,"h":..,"visible":true,"config":{...}} ] }
 *
 * 内置模板（[BUILTIN_NAME]）为代码常量，不入库 → 天然只读、不可覆盖/删除。
 * 旧单页文件 home_layout.json 首次启动自动迁移为"默认布局"（迁移后保留旧文件不删）。
 *
 * 加载时若保存屏幕尺寸与当前不一致，按比例缩放全部坐标尺寸（保持相对布局），
 * 并 clamp 到最小 20×20、屏幕边界内。
 */
object LayoutRepository {
    private const val TAG = "LayoutRepository"
    private const val FILE = "layouts.json"
    private const val LEGACY_FILE = "home_layout.json"

    /** 内置只读模板名（代码常量，不入库） */
    const val BUILTIN_NAME = "经典布局"
    /** 迁移/首启用户布局默认名 */
    const val DEFAULT_NAME = "默认布局"
    const val MIN_SIZE = 40

    fun file(context: Context): File = File(context.filesDir, FILE)
    private fun legacyFile(context: Context): File = File(context.filesDir, LEGACY_FILE)

    /** 内置模板是否为真 */
    fun isBuiltIn(name: String): Boolean = name == BUILTIN_NAME

    /** 内置模板（单页默认布局；只读，不入库） */
    fun builtin(screenW: Int, screenH: Int): NamedLayout =
        NamedLayout(BUILTIN_NAME, listOf(defaultLayout(screenW, screenH)))

    // ── 命名布局仓库 ─────────────────────────────────

    /** 全部布局名（内置置顶 + 用户，去重） */
    fun listNames(context: Context): List<String> {
        val names = ArrayList<String>()
        names.add(BUILTIN_NAME)
        for (l in loadRepo(context).layouts) if (l.name != BUILTIN_NAME) names.add(l.name)
        return names
    }

    fun list(context: Context): List<NamedLayout> {
        val out = ArrayList<NamedLayout>()
        // 内置以代码生成（屏幕尺寸未知时用 0 占位，加载时 normalize）
        out.add(NamedLayout(BUILTIN_NAME, emptyList()))
        out.addAll(loadRepo(context).layouts)
        return out
    }

    /** 读取命名布局；经典布局优先取持久化修改版（重置后回落代码模板），其余走文件。无/失败返回 null。 */
    fun load(context: Context, name: String, screenW: Int, screenH: Int): NamedLayout? {
        val l = loadRepo(context).layouts.firstOrNull { it.name == name }
        if (l == null && isBuiltIn(name)) return builtin(screenW, screenH)
        return l?.copy(pages = l.pages.map { normalize(it, screenW, screenH) })
    }

    /** 保存命名布局（经典布局的修改同样写盘；重置用 [delete] 清除） */
    fun save(context: Context, layout: NamedLayout): Boolean {
        val repo = loadRepo(context)
        val idx = repo.layouts.indexOfFirst { it.name == layout.name }
        val layouts = ArrayList(repo.layouts)
        if (idx >= 0) layouts[idx] = layout else layouts.add(layout)
        return writeRepo(context, Repo(repo.version, repo.active, layouts))
    }

    /** 删除命名布局（经典布局的持久化修改也可删除以恢复出厂模板） */
    fun delete(context: Context, name: String): Boolean {
        val repo = loadRepo(context)
        val layouts = ArrayList(repo.layouts)
        if (!layouts.removeAll { it.name == name }) return false
        val active = if (repo.active == name) (layouts.firstOrNull()?.name ?: BUILTIN_NAME) else repo.active
        return writeRepo(context, Repo(repo.version, active, layouts))
    }

    /** 当前激活布局名；缺失回落第一用户/内置 */
    fun activeName(context: Context): String {
        val repo = loadRepo(context)
        if (repo.active.isNotBlank()) return repo.active
        return repo.layouts.firstOrNull()?.name ?: BUILTIN_NAME
    }

    fun setActive(context: Context, name: String) {
        val repo = loadRepo(context)
        writeRepo(context, repo.copy(active = name))
    }

    /**
     * 加载激活布局：先迁移旧文件，再返回 active 对应布局。
     * 无任何数据 → 内置模板（用户改动需"另存为"）。
     */
    fun loadActive(context: Context, screenW: Int, screenH: Int): NamedLayout {
        migrateIfNeeded(context)
        return load(context, activeName(context), screenW, screenH)
            ?: builtin(screenW, screenH)
    }

    /** 首次启动迁移：仅当 layouts.json 不存在且旧 home_layout.json 存在时执行 */
    fun migrateIfNeeded(context: Context) {
        val repoFile = file(context)
        val legacy = legacyFile(context)
        if (repoFile.exists() || !legacy.isFile || legacy.length() <= 0L) return
        val old = try { fromJson(legacy.readText()) } catch (e: Exception) {
            Log.w(TAG, "legacy parse failed", e); null
        } ?: return
        writeRepo(context, Repo(1, DEFAULT_NAME, listOf(NamedLayout(DEFAULT_NAME, listOf(old)))))
    }

    // ── 单页工具（原样保留，供 builtin/迁移/加载复用） ─────

    /** 校正：屏幕尺寸变化按比例缩放；越界/小于最小尺寸的 widget 收回边界内。
     *  同宽且高度差 ≤64px（状态栏显隐导致的画布微调）不缩放，避免控件被反复放大变形 */
    fun normalize(layout: HomeLayout, screenW: Int, screenH: Int): HomeLayout {
        if (layout.screenWidth <= 0 || layout.screenHeight <= 0 || layout.widgets.isEmpty()) {
            return layout.copy(screenWidth = screenW, screenHeight = screenH)
        }
        val drift = layout.screenWidth == screenW &&
            kotlin.math.abs(layout.screenHeight - screenH) <= 64
        val rx = screenW.toFloat() / layout.screenWidth
        val ry = screenH.toFloat() / layout.screenHeight
        val scaled = layout.widgets.map { s ->
            val w = if (drift) s.w else max(MIN_SIZE, (s.w * rx).roundToInt()).coerceAtMost(screenW)
            val h = if (drift) s.h else max(MIN_SIZE, (s.h * ry).roundToInt()).coerceAtMost(screenH)
            WidgetSpec(
                id = s.id, type = s.type,
                x = s.x.coerceIn(0, maxOf(0, screenW - w)),
                y = s.y.coerceIn(0, maxOf(0, screenH - h)),
                w = w, h = h,
                visible = s.visible, config = s.config
            )
        }
        // 保留每布局独立参数（间距/边距/状态栏），仅重排 widgets
        return HomeLayout(
            HomeLayout.CURRENT_VERSION, screenW, screenH, scaled,
            layout.gap, layout.margin, layout.hideStatusBar
        )
    }

    /** 无保存布局时的默认布局（取自"我的布局"）：左窄条应用列表（7 条，首条抽屉）/中 VD（默认高德）/右音乐卡，无底栏 */
    fun defaultLayout(screenW: Int, screenH: Int): HomeLayout {
        val pad = 10
        val gap = 10
        val contentH = screenH - pad * 2
        // 应用列表一条竖栏：仅比默认图标（48px）+ 内边距（8px×2）略宽
        val leftW = 76
        val rightW = 300
        val midW = screenW - pad * 2 - leftW - rightW - gap * 2
        val nextId = intArrayOf(1)
        fun spec(type: String, x: Int, y: Int, w: Int, h: Int, cfg: Map<String, String> = emptyMap()) =
            WidgetSpec(nextId[0]++, type, x, y, w, h, true, cfg)
        val rightX = pad + leftW + gap + midW + gap
        val widgets = listOf(
            // 应用列表：首条固定应用抽屉，其余默认系统应用，共 7 条
            spec(WidgetTypes.APPLIST, pad, pad, leftW, contentH, mapOf(
                AppListWidget.CFG_COUNT to "7"
            )),
            // VD 默认绑定高德地图车机版
            spec(WidgetTypes.VD, pad + leftW + gap, pad, midW, contentH, mapOf(
                CFG_VD_PKG to "com.autonavi.amapauto"
            )),
            // 音乐卡（歌词 + 控制按钮），默认绑定 QQ 音乐
            spec(WidgetTypes.LYRICS, rightX, pad, rightW, contentH, mapOf(
                LyricsWidget.CFG_MUSIC_PKG to "com.tencent.qqmusiccar",
                LyricsWidget.CFG_LINES to "8",
                LyricsWidget.CFG_SIZE_CUR to "20",
                LyricsWidget.CFG_SIZE_OTHER to "18",
                LyricsWidget.CFG_SIZE_TIME to "15",
                LyricsWidget.CFG_SIZE_ARTIST to "15",
                LyricsWidget.CFG_GAP to "10"
            ))
        )
        return HomeLayout(HomeLayout.CURRENT_VERSION, screenW, screenH, widgets, gap = 10, margin = 10)
    }

    // ── JSON ─────────────────────────────────────────

    private fun homeLayoutToJson(l: HomeLayout): JSONObject = JSONObject().apply {
        put("version", l.version)
        put("screenWidth", l.screenWidth)
        put("screenHeight", l.screenHeight)
        put("gap", l.gap)
        put("margin", l.margin)
        put("hideStatusBar", l.hideStatusBar)
        put("widgets", JSONArray().apply {
            for (s in l.widgets) put(JSONObject().apply {
                put("id", s.id)
                put("type", s.type)
                put("x", s.x); put("y", s.y); put("w", s.w); put("h", s.h)
                put("visible", s.visible)
                put("config", JSONObject().apply { for ((k, v) in s.config) put(k, v) })
            })
        })
    }

    private fun homeLayoutFromJson(o: JSONObject): HomeLayout {
        val arr = o.optJSONArray("widgets") ?: JSONArray()
        val widgets = ArrayList<WidgetSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cfg = LinkedHashMap<String, String>()
            val cj = c.optJSONObject("config")
            if (cj != null) for (k in cj.keys()) cfg[k] = cj.optString(k, "")
            widgets.add(
                WidgetSpec(
                    id = c.optInt("id", i + 1),
                    type = c.optString("type", ""),
                    x = c.optInt("x", 0), y = c.optInt("y", 0),
                    w = c.optInt("w", 300), h = c.optInt("h", 200),
                    visible = c.optBoolean("visible", true),
                    config = cfg
                )
            )
        }
        return HomeLayout(
            o.optInt("version", HomeLayout.CURRENT_VERSION),
            o.optInt("screenWidth", 0), o.optInt("screenHeight", 0),
            widgets,
            o.optInt("gap", HomeLayout.DEFAULT_GAP),
            o.optInt("margin", 0),
            o.optBoolean("hideStatusBar", false)
        )
    }

    private data class Repo(val version: Int, val active: String, val layouts: List<NamedLayout>)

    private fun loadRepo(context: Context): Repo = try {
        val f = file(context)
        if (f.isFile && f.length() > 0L) {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("layouts") ?: JSONArray()
            val layouts = ArrayList<NamedLayout>(arr.length())
            for (i in 0 until arr.length()) {
                val lo = arr.optJSONObject(i) ?: continue
                val name = lo.optString("name", "")
                val pages = ArrayList<HomeLayout>()
                val pa = lo.optJSONArray("pages")
                if (pa != null) for (j in 0 until pa.length()) {
                    val po = pa.optJSONObject(j) ?: continue
                    pages.add(homeLayoutFromJson(po))
                }
                layouts.add(NamedLayout(name, pages))
            }
            Repo(o.optInt("version", 1), o.optString("active", ""), layouts)
        } else {
            Repo(1, "", emptyList())
        }
    } catch (e: Exception) {
        Log.w(TAG, "loadRepo failed", e)
        Repo(1, "", emptyList())
    }

    private fun writeRepo(context: Context, repo: Repo): Boolean = try {
        val o = JSONObject().apply {
            put("version", repo.version)
            put("active", repo.active)
            put("layouts", JSONArray().apply {
                for (l in repo.layouts) put(JSONObject().apply {
                    put("name", l.name)
                    put("pages", JSONArray().apply {
                        for (p in l.pages) put(homeLayoutToJson(p))
                    })
                })
            })
        }
        file(context).writeText(o.toString(2))
        true
    } catch (e: Exception) {
        Log.w(TAG, "writeRepo failed", e)
        false
    }

    /** 旧单页文件读取（仅迁移用） */
    private fun fromJson(text: String): HomeLayout {
        val o = JSONObject(text)
        val arr = o.optJSONArray("widgets") ?: JSONArray()
        val widgets = ArrayList<WidgetSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cfg = LinkedHashMap<String, String>()
            val cj = c.optJSONObject("config")
            if (cj != null) for (k in cj.keys()) cfg[k] = cj.optString(k, "")
            widgets.add(
                WidgetSpec(
                    id = c.optInt("id", i + 1),
                    type = c.optString("type", ""),
                    x = c.optInt("x", 0), y = c.optInt("y", 0),
                    w = c.optInt("w", 300), h = c.optInt("h", 200),
                    visible = c.optBoolean("visible", true),
                    config = cfg
                )
            )
        }
        return HomeLayout(
            o.optInt("version", HomeLayout.CURRENT_VERSION),
            o.optInt("screenWidth", 0), o.optInt("screenHeight", 0),
            widgets
        )
    }
}
