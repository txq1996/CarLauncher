package com.android.launcher37

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.android.launcher37.home.NaviPanelDelegate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 桌面设置：左侧选项卡（布局/车速/音乐/通用）+ 右侧内容面板。
 *
 * 尺寸类参数为 px 滑条，全部即时写入 `launcher37_config`，
 * 关闭桌面设置后自动重启桌面生效（LauncherActivity.onCreate 快照）。
 */
class SettingsActivity : Activity() {

    companion object {
        // ── 布局（px）─────────────────
        const val KEY_PAGE_PADDING = "layout_page_padding"
        const val KEY_CARD_GAP = "layout_card_gap"
        const val KEY_SPEED_CARD_W = "layout_speed_card_w"
        const val KEY_MUSIC_CARD_H = "layout_music_card_h"
        const val KEY_DOCK_HEIGHT = "dock_height"
        const val KEY_DOCK_ICON_SIZE = "dock_icon_size"
        const val KEY_DOCK_COLUMNS = "dock_columns"

        // ── 车速区域显隐（导航模式） ────────────────
        const val KEY_SHOW_NAVI_SPEED = "show_navi_speed"
        const val KEY_SHOW_NAVI_KMH = "show_navi_kmh"
        const val KEY_SHOW_NAVI_LIMIT = "show_navi_limit"
        const val KEY_SHOW_NAVI_TRAFFIC = "show_navi_traffic"
        // ── 车速区域显隐（巡航模式） ────────────────
        const val KEY_SHOW_CRUISE_SPEED = "show_cruise_speed"
        const val KEY_SHOW_CRUISE_KMH = "show_cruise_kmh"
        const val KEY_SHOW_CRUISE_LIMIT = "show_cruise_limit"
        const val KEY_SHOW_CRUISE_TRAFFIC = "show_cruise_traffic"
        // 导航行显隐（每个字段独立）
        const val KEY_SHOW_NAVI_TURN = "show_navi_turn"
        const val KEY_SHOW_NAVI_ROAD = "show_navi_road"
        const val KEY_SHOW_NAVI_DEST = "show_navi_dest"
        const val KEY_SHOW_NAVI_ETA = "show_navi_eta"
        const val KEY_SHOW_NAVI_ETA_TEXT = "show_navi_eta_text"
        const val KEY_SHOW_NAVI_LIGHT_COUNT = "show_navi_light_count"
        const val KEY_SHOW_NAVI_EXIT = "show_navi_exit"
        const val KEY_SHOW_NAVI_DIRECTION = "show_navi_direction"
        const val KEY_SHOW_NAVI_ALERT = "show_navi_alert"
        // 巡航行显隐（每个字段独立）
        const val KEY_SHOW_CRUISE_ROAD = "show_cruise_road"
        const val KEY_SHOW_CRUISE_DIRECTION = "show_cruise_direction"
        const val KEY_SHOW_CRUISE_ALERT = "show_cruise_alert"
        const val KEY_SHOW_MUSIC_TITLE = "show_music_title"
        const val KEY_SHOW_MUSIC_ARTIST = "show_music_artist"
        const val KEY_SHOW_MUSIC_TIME = "show_music_time"
        const val KEY_SHOW_MUSIC_BAR = "show_music_bar"
        // 卡片显隐
        const val KEY_SHOW_SPEED_CARD = "show_speed_card"
        const val KEY_SHOW_MUSIC_CARD = "show_music_card"
        const val KEY_SHOW_TIME = "show_time"
        const val KEY_SHOW_DOCK = "show_dock"
        const val KEY_SHOW_DOCK_LABEL = "show_dock_label"
        // 时间
        const val KEY_TIME_CARD_H = "time_card_h"
        const val KEY_TS_TIME = "ts_time"
        const val KEY_TIME_FORMAT = "time_format"

        /** bool 开关全集（SettingsSnapshot.load 按 key=true 缺省快照） */
        val SHOW_KEYS = arrayOf(
            KEY_SHOW_NAVI_SPEED, KEY_SHOW_NAVI_KMH, KEY_SHOW_NAVI_LIMIT, KEY_SHOW_NAVI_TRAFFIC,
            KEY_SHOW_CRUISE_SPEED, KEY_SHOW_CRUISE_KMH, KEY_SHOW_CRUISE_LIMIT, KEY_SHOW_CRUISE_TRAFFIC,
            KEY_SHOW_NAVI_TURN, KEY_SHOW_NAVI_ROAD, KEY_SHOW_NAVI_DEST,
            KEY_SHOW_NAVI_ETA, KEY_SHOW_NAVI_ETA_TEXT, KEY_SHOW_NAVI_LIGHT_COUNT,
            KEY_SHOW_NAVI_EXIT, KEY_SHOW_NAVI_DIRECTION, KEY_SHOW_NAVI_ALERT,
            KEY_SHOW_CRUISE_ROAD, KEY_SHOW_CRUISE_DIRECTION, KEY_SHOW_CRUISE_ALERT,
            KEY_SHOW_MUSIC_TITLE, KEY_SHOW_MUSIC_ARTIST, KEY_SHOW_MUSIC_TIME,
            KEY_SHOW_MUSIC_BAR,
            KEY_SHOW_SPEED_CARD, KEY_SHOW_MUSIC_CARD, KEY_SHOW_TIME,
            KEY_SHOW_DOCK, KEY_SHOW_DOCK_LABEL
        )

        /** 首次安装无 SP 值时的默认显隐（其余未列出项默认 true） */
        val SHOW_DEFAULTS: Map<String, Boolean> = mapOf(
            KEY_SHOW_NAVI_DEST to false,
            KEY_SHOW_NAVI_ETA_TEXT to false,
            KEY_SHOW_NAVI_LIGHT_COUNT to false,
            KEY_SHOW_NAVI_EXIT to false,
            KEY_SHOW_NAVI_DIRECTION to false,
            KEY_SHOW_CRUISE_DIRECTION to false,
            KEY_SHOW_TIME to false
        )

        // ── 字号/尺寸（px，int）──────────────────
        // 车速区域字号（导航模式）
        const val KEY_TS_NAVI_SPEED = "ts_navi_speed"
        const val KEY_TS_NAVI_KMH = "ts_navi_kmh"
        const val KEY_TS_NAVI_LIMIT = "ts_navi_limit"
        const val KEY_TS_NAVI_TRAFFIC_SEC = "ts_navi_traffic_sec"
        // 车速区域字号（巡航模式）
        const val KEY_TS_CRUISE_SPEED = "ts_cruise_speed"
        const val KEY_TS_CRUISE_KMH = "ts_cruise_kmh"
        const val KEY_TS_CRUISE_LIMIT = "ts_cruise_limit"
        const val KEY_TS_CRUISE_TRAFFIC_SEC = "ts_cruise_traffic_sec"
        // 导航行字号
        const val KEY_TS_NAVI_TURN = "ts_navi_turn"
        const val KEY_TS_NAVI_ROAD = "ts_navi_road"
        const val KEY_TS_NAVI_DEST = "ts_navi_dest"
        const val KEY_TS_NAVI_ETA = "ts_navi_eta"
        const val KEY_TS_NAVI_ETA_TEXT = "ts_navi_eta_text"
        const val KEY_TS_NAVI_LIGHT_COUNT = "ts_navi_light_count"
        const val KEY_TS_NAVI_EXIT = "ts_navi_exit"
        const val KEY_TS_NAVI_DIRECTION = "ts_navi_direction"
        const val KEY_TS_NAVI_ALERT = "ts_navi_alert"
        // 巡航行字号
        const val KEY_TS_CRUISE_ROAD = "ts_cruise_road"
        const val KEY_TS_CRUISE_DIRECTION = "ts_cruise_direction"
        const val KEY_TS_CRUISE_ALERT = "ts_cruise_alert"
        // 音乐
        const val KEY_TS_MUSIC_TITLE = "ts_music_title"
        const val KEY_TS_MUSIC_ARTIST = "ts_music_artist"
        const val KEY_TS_MUSIC_TIME = "ts_music_time"

        /** int 尺寸快照表（key 顺序与默认值一一对应） */
        val INT_KEYS = arrayOf(
            KEY_PAGE_PADDING, KEY_CARD_GAP,
            KEY_SPEED_CARD_W, KEY_MUSIC_CARD_H, KEY_DOCK_HEIGHT, KEY_DOCK_ICON_SIZE, KEY_DOCK_COLUMNS,
            KEY_TS_NAVI_SPEED, KEY_TS_NAVI_KMH, KEY_TS_NAVI_LIMIT, KEY_TS_NAVI_TRAFFIC_SEC,
            KEY_TS_CRUISE_SPEED, KEY_TS_CRUISE_KMH, KEY_TS_CRUISE_LIMIT, KEY_TS_CRUISE_TRAFFIC_SEC,
            KEY_TS_NAVI_TURN, KEY_TS_NAVI_ROAD, KEY_TS_NAVI_DEST,
            KEY_TS_NAVI_ETA, KEY_TS_NAVI_ETA_TEXT, KEY_TS_NAVI_LIGHT_COUNT,
            KEY_TS_NAVI_EXIT, KEY_TS_NAVI_DIRECTION, KEY_TS_NAVI_ALERT,
            KEY_TS_CRUISE_ROAD, KEY_TS_CRUISE_DIRECTION, KEY_TS_CRUISE_ALERT,
            KEY_TS_MUSIC_TITLE, KEY_TS_MUSIC_ARTIST, KEY_TS_MUSIC_TIME,
            KEY_TIME_CARD_H, KEY_TS_TIME, KEY_TIME_FORMAT,
            KEY_DRAWER_ICON_SIZE, KEY_DRAWER_ICON_GAP, KEY_DRAWER_LABEL_SIZE,
            KEY_DRAWER_WIDTH_PCT, KEY_DRAWER_HEIGHT_PCT,
            KEY_PIP_START_DELAY, KEY_VD_LAUNCH_DELAY
        )

        val INT_DEFAULTS = intArrayOf(
            10, 10, 260, 180, 80, 44, 10,
            110, 20, 17, 36,
            110, 20, 17, 36,
            36, 26, 15, 17, 17, 17, 17, 17, 17,
            26, 17, 17,
            24, 15, 15,
            60, 28, 1,
            64, 8, 17,
            75, 75,
            250, 250
        )

        // ── 行序键（持久化到 SP）─────────────────
        const val KEY_NAVI_ORDER = "navi_row_order"
        const val KEY_CRUISE_ORDER = "cruise_row_order"

        // ── 全部应用外观（抽屉/悬浮窗共用，px/int）──────────────────
        const val KEY_DRAWER_ICON_SIZE = "drawer_icon_size"
        const val KEY_DRAWER_ICON_GAP = "drawer_icon_gap"
        const val KEY_DRAWER_LABEL_SIZE = "drawer_label_size"
        /** 全部应用弹窗宽度占屏幕百分比（50~95，默认 75） */
        const val KEY_DRAWER_WIDTH_PCT = "drawer_width_pct"
        /** 全部应用弹窗高度占屏幕百分比（50~95，默认 75） */
        const val KEY_DRAWER_HEIGHT_PCT = "drawer_height_pct"

        // ── 通用 ───────────────────────
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        const val KEY_HOME_DIRECT_APP_DRAWER = "home_direct_app_drawer"
        /** 桌面拉起 PIP 前的延迟毫秒（0=立即；恢复 d4584a0 删除的 PIP_START_DELAY_MS=250） */
        const val KEY_PIP_START_DELAY = "pip_start_delay"
        /** PipService 拉起/拉回任务前的延迟毫秒（0=立即；恢复 d4584a0 删除的 LAUNCH_DELAY_MS=500） */
        const val KEY_VD_LAUNCH_DELAY = "vd_launch_delay"
    }

    private lateinit var mTabs: Array<TextView>
    private lateinit var mPanels: Array<View>
    private lateinit var mTabActiveBg: GradientDrawable
    /** 导航全部行序（车速 + 行序，持久化 navi_row_order） */
    private val mNaviOrder: ArrayList<String> = ArrayList()
    /** 巡航全部行序（车速 + 行序，持久化 cruise_row_order） */
    private val mCruiseOrder: ArrayList<String> = ArrayList()
    private var mUpdater: UpdateChecker? = null
    private var mTvUpdateStatus: TextView? = null

    /** 设置页导航分区子标题 */
    private var mTvNaviHeader: TextView? = null
    /** 设置页巡航分区子标题 */
    private var mTvCruiseHeader: TextView? = null

    // ── 设置项定义：key, 显示名, 字号 key, 默认字号, 显示开关 key（空=无开关）, 默认显隐, 是否可排序 ──
    private data class SettingItem(
        val key: String,
        val label: String,
        val fontKey: String,
        val fontDefault: Int,
        val showKey: String,
        val showDefault: Boolean,
        val orderable: Boolean
    )

    /** 车速卡字号 key：范围 [0,150]；其余 [0,250] */
    private val FONT_RANGE_SMALL = setOf(
        KEY_TS_NAVI_SPEED, KEY_TS_NAVI_KMH, KEY_TS_NAVI_LIMIT, KEY_TS_NAVI_TRAFFIC_SEC,
        KEY_TS_CRUISE_SPEED, KEY_TS_CRUISE_KMH, KEY_TS_CRUISE_LIMIT, KEY_TS_CRUISE_TRAFFIC_SEC
    )

    /**
     * 导航模式全部设置项（车速 + 行序），统一排序。
     */
    private val naviAllItems: List<SettingItem> = listOf(
        SettingItem("speed",         "车速数字",      KEY_TS_NAVI_SPEED,        110, KEY_SHOW_NAVI_SPEED,        true, true),
        SettingItem("speed_unit",    "车速单位",      KEY_TS_NAVI_KMH,          20,  KEY_SHOW_NAVI_KMH,          true, true),
        SettingItem("limit",         "道路限速",      KEY_TS_NAVI_LIMIT,        17,  KEY_SHOW_NAVI_LIMIT,        true, true),
        SettingItem("traffic",       "红绿灯倒计时",  KEY_TS_NAVI_TRAFFIC_SEC,  36,  KEY_SHOW_NAVI_TRAFFIC,      true, true),
        SettingItem("navi_turn",     "转向图标与距离", KEY_TS_NAVI_TURN,         36,  KEY_SHOW_NAVI_TURN,         true, true),
        SettingItem("navi_road",     "路名",          KEY_TS_NAVI_ROAD,         26,  KEY_SHOW_NAVI_ROAD,         true, true),
        SettingItem("navi_dest",     "终点名称",      KEY_TS_NAVI_DEST,         15,  KEY_SHOW_NAVI_DEST,         false, true),
        SettingItem("navi_eta",      "剩余距离与时间",  KEY_TS_NAVI_ETA,          17,  KEY_SHOW_NAVI_ETA,          true, true),
        SettingItem("navi_eta_text", "预计到达时间",   KEY_TS_NAVI_ETA_TEXT,     17,  KEY_SHOW_NAVI_ETA_TEXT,     false, true),
        SettingItem("navi_light_count","剩余红绿灯数", KEY_TS_NAVI_LIGHT_COUNT,  17,  KEY_SHOW_NAVI_LIGHT_COUNT,  false, true),
        SettingItem("navi_exit",     "出口信息",      KEY_TS_NAVI_EXIT,         17,  KEY_SHOW_NAVI_EXIT,         false, true),
        SettingItem("navi_direction","车头方向",      KEY_TS_NAVI_DIRECTION,    17,  KEY_SHOW_NAVI_DIRECTION,    false, true),
        SettingItem("navi_alert",    "电子眼/服务区", KEY_TS_NAVI_ALERT,        17,  KEY_SHOW_NAVI_ALERT,        true, true)
    )

    /**
     * 巡航模式全部设置项（车速 + 行序），统一排序。
     */
    private val cruiseAllItems: List<SettingItem> = listOf(
        SettingItem("speed",             "车速数字",      KEY_TS_CRUISE_SPEED,        110, KEY_SHOW_CRUISE_SPEED,        true, true),
        SettingItem("speed_unit",        "车速单位",      KEY_TS_CRUISE_KMH,          20,  KEY_SHOW_CRUISE_KMH,          true, true),
        SettingItem("limit",             "道路限速",      KEY_TS_CRUISE_LIMIT,        17,  KEY_SHOW_CRUISE_LIMIT,        true, true),
        SettingItem("traffic",           "红绿灯倒计时",  KEY_TS_CRUISE_TRAFFIC_SEC,  36,  KEY_SHOW_CRUISE_TRAFFIC,      true, true),
        SettingItem("cruise_road",       "当前路名",      KEY_TS_CRUISE_ROAD,         26,  KEY_SHOW_CRUISE_ROAD,         true, true),
        SettingItem("cruise_direction",  "车头方向",      KEY_TS_CRUISE_DIRECTION,    17,  KEY_SHOW_CRUISE_DIRECTION,    false, true),
        SettingItem("cruise_alert",      "电子眼/服务区", KEY_TS_CRUISE_ALERT,        17,  KEY_SHOW_CRUISE_ALERT,        true, true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        mTabActiveBg = GradientDrawable().apply {
            cornerRadius = 18f
            setColor(resources.getColor(R.color.surface, theme))
        }
        mTabs = arrayOf(
            findViewById(R.id.tab_layout),
            findViewById(R.id.tab_speed),
            findViewById(R.id.tab_music),
            findViewById(R.id.tab_time),
            findViewById(R.id.tab_dock),
            findViewById(R.id.tab_apps),
            findViewById(R.id.tab_general)
        )
        mPanels = arrayOf(
            findViewById(R.id.panel_layout),
            findViewById(R.id.panel_speed),
            findViewById(R.id.panel_music),
            findViewById(R.id.panel_time),
            findViewById(R.id.panel_dock),
            findViewById(R.id.panel_apps),
            findViewById(R.id.panel_general)
        )
        for (i in mTabs.indices) {
            val index = i
            mTabs[i].setOnClickListener { switchTab(index) }
        }
        switchTab(0)

        bindLayoutTab()
        bindSpeedTab()
        bindMusicTab()
        bindTimeTab()
        bindDockTab()
        bindAppsTab()
        bindDrawerLooks()
        bindGeneralTab()
        findViewById<Button>(R.id.btn_restart_launcher).setOnClickListener { restartLauncher() }
    }

    override fun onDestroy() {
        // mUpdater（= UpdateChecker）持 application 强引用 + 可选 Activity 引用
        // （mActivity）；显式 release 清空 mActivity 避免 UpdateChecker 异步
        // 回调进死 Activity 抛 BadTokenException。
        mUpdater?.release()
        mUpdater = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(this, LauncherActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    /**
     * 重建 LauncherActivity 让刚才改的设置生效（`onCreate` 走 `SettingsSnapshot.load` 重新读 SP）。
     * `CLEAR_TASK + NEW_TASK` 保证 task 内所有 Activity（含自己）被销毁后，用新 Intent 重新
     * 启动 LauncherActivity —— 进程保留，PipService 不受影响。`onCreate` 会把
     * 5 个 delegate 全部重新构造，新 SP 字段自然生效。
     */
    private fun restartLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            // 带 HOME category：重启后进桌面，而不是走"其他 App 上弹悬浮窗"路径
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    /** 左侧选项卡切换：选中项卡片底 + 主文字色，其余透明 + 次文字色 */
    private fun switchTab(index: Int) {
        val primary = resources.getColor(R.color.foreground, theme)
        val secondary = resources.getColor(R.color.foreground_secondary, theme)
        for (i in mTabs.indices) {
            val active = i == index
            mTabs[i].background = if (active) mTabActiveBg else null
            mTabs[i].setTextColor(if (active) primary else secondary)
            mTabs[i].paint.isFakeBoldText = active
            mPanels[i].visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    private fun bindLayoutTab() {
        val box = findViewById<LinearLayout>(R.id.box_layout_seeks)
        bindSeek(box, "页面边距", KEY_PAGE_PADDING, 10, 0, 40)
        bindSeek(box, "部件间距", KEY_CARD_GAP, 10, 0, 40)
        bindSeek(box, "左侧栏宽度", KEY_SPEED_CARD_W, 260, 220, 400)
        bindSeek(box, "音乐卡高度", KEY_MUSIC_CARD_H, 180, 140, 300)
        bindSeek(box, "时间卡高度", KEY_TIME_CARD_H, 60, 40, 200)
        // 卡片显隐（布局内统一，显示时间在车速上方）
        val cbStatus = findViewById<CheckBox>(R.id.cb_hide_status_bar)
        val hide = prefs().getBoolean(KEY_HIDE_STATUS_BAR, false)
        cbStatus.isChecked = !hide
        cbStatus.setOnCheckedChangeListener { _, isChecked ->
            prefs().edit().putBoolean(KEY_HIDE_STATUS_BAR, !isChecked).apply()
        }
        bindCheck(R.id.cb_show_time, KEY_SHOW_TIME)
        bindCheck(R.id.cb_show_speed_card, KEY_SHOW_SPEED_CARD)
        bindCheck(R.id.cb_show_music_card, KEY_SHOW_MUSIC_CARD)
        bindCheck(R.id.cb_show_dock, KEY_SHOW_DOCK)
    }

    private fun bindDockTab() {
        bindCheck(R.id.cb_show_dock_label, KEY_SHOW_DOCK_LABEL)
        val box = findViewById<LinearLayout>(R.id.box_dock_seeks)
        box.removeAllViews()
        bindSeek(box, "底栏高度", KEY_DOCK_HEIGHT, 80, 60, 120)
        bindSeek(box, "底栏图标大小", KEY_DOCK_ICON_SIZE, 44, 32, 64)
        bindSeek(box, "底栏图标数量", KEY_DOCK_COLUMNS, 10, 5, 10, unit = "个")
    }

    // ── 应用选项卡（抽屉排序与隐藏 + 全部应用外观） ──────────────────────

    /** 应用选项卡行模型（tag / 显示名 / 图标） */
    private data class OrderRow(val tag: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    /** 全部可启动应用入口（IO 线程预载，默认"用户优先+字典序"） */
    private var mAppEntries: List<android.content.pm.ResolveInfo> = emptyList()

    /** 抽屉项顺序（tag 全集：功能项/分屏/应用；含隐藏项，隐藏仅决定显示与否） */
    private val mAppOrder: ArrayList<String> = ArrayList()

    /** 抽屉隐藏应用集合 */
    private val mAppHidden: LinkedHashSet<String> = LinkedHashSet()

    /** IO 线程预载应用列表后合并已保存顺序，再渲染行 */
    private fun bindAppsTab() {
        mAppHidden.clear()
        mAppHidden.addAll(Store.drawerHidden(this))
        mAppOrder.clear()
        mAppOrder.addAll(Store.drawerOrder(this))
        SharedExecutor.io().execute {
            val entries = AppQuery.launcherEntriesSorted(this)
            // 预热 icon 缓存，主线程渲染行时不做 binder 调用
            for (ri in entries) Store.normalizedIcon(this, AppQuery.appId(ri))
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                mAppEntries = entries
                rebuildAppsPanel()
            }
        }
    }

    /** 与 DrawerAdapter 构造顺序一致的默认行序（功能项 → 分屏 → 应用） */
    private fun buildDefaultRows(): List<OrderRow> {
        val rows = ArrayList<OrderRow>()
        val names = listOf("桌面设置", "回家", "公司", "清理", "分屏", "返回主页", "重启桌面")
        val tags = listOf(
            DrawerAdapter.TAG_SETTINGS, DrawerAdapter.TAG_HOME, DrawerAdapter.TAG_COMPANY,
            DrawerAdapter.TAG_CLEAN, DrawerAdapter.TAG_SPLIT_NEW, DrawerAdapter.TAG_GOHOME,
            DrawerAdapter.TAG_RESTART
        )
        val emojis = listOf(
            MapFeature.SETTINGS_EMOJI, MapFeature.HOME_EMOJI, MapFeature.COMPANY_EMOJI,
            MapFeature.CLEAN_EMOJI, MapFeature.SPLIT_EMOJI, MapFeature.GOHOME_EMOJI,
            MapFeature.RESTART_EMOJI
        )
        for (i in tags.indices) rows.add(OrderRow(tags[i], names[i], Store.normalizedEmoji(this, emojis[i])))
        val splits = SplitRepository.load(this)
        for (i in splits.indices) {
            val pair = splits[i]
            rows.add(OrderRow(
                "${DrawerAdapter.SPLIT_PREFIX}$i",
                "${Store.label(this, pair[0])}|${Store.label(this, pair[1])}",
                Store.normalizedSplitIcon(this, pair[0], pair[1])
            ))
        }
        for (ri in mAppEntries) {
            val id = AppQuery.appId(ri)
            rows.add(OrderRow(id, Store.label(this, id), Store.normalizedIcon(this, id)))
        }
        return rows
    }

    private fun rebuildAppsPanel() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.box_apps) ?: return
        val defaults = buildDefaultRows()
        val byTag = HashMap<String, OrderRow>(defaults.size * 2)
        for (r in defaults) byTag[r.tag] = r
        // 合并：已保存顺序在前（仅保留仍存在的项），新项（新装应用/分屏/返回主页）按默认序追加
        val merged = ArrayList<OrderRow>(defaults.size)
        val seen = HashSet<String>()
        for (tag in mAppOrder) {
            byTag[tag]?.let { if (seen.add(tag)) merged.add(it) }
        }
        for (r in defaults) if (seen.add(r.tag)) merged.add(r)
        mAppOrder.clear()
        mAppOrder.addAll(merged.map { it.tag })
        // 每次进入设置页重建 adapter，避免跨 Activity 实例复用
        mOrderAdapter = OrderAdapter()
        list.adapter = mOrderAdapter
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        mItemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(mOrderAdapter!!.DragCallback())
        mItemTouchHelper!!.attachToRecyclerView(list)
        mOrderAdapter!!.setRows(merged)
    }

    /**
     * 排序/隐藏列表适配器：长按行（或按住 ≡ 手柄）上下拖动排序，
     * checkbox 勾选隐藏。数据顺序 = mAppOrder 顺序。
     */
    private inner class OrderAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<OrderAdapter.VH>() {

        private val rows = ArrayList<OrderRow>()
        /** 拖动中顺序已改：松手时持久化 */
        private var mDragDirty = false

        fun setRows(list: List<OrderRow>) {
            rows.clear()
            rows.addAll(list)
            mDragDirty = false
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            java.util.Collections.swap(rows, from, to)
            notifyItemMoved(from, to)
            mAppOrder.clear()
            mAppOrder.addAll(rows.map { it.tag })
            mDragDirty = true
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_app_order_row, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.icon.setImageDrawable(row.icon)
            holder.label.text = row.label
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = row.tag in mAppHidden
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) mAppHidden.add(row.tag) else mAppHidden.remove(row.tag)
                Store.saveDrawerHidden(this@SettingsActivity, mAppHidden)
            }
            // 手柄拖拽（长按行也可拖：ItemTouchHelper.isLongPressDragEnabled 默认 true）
            holder.itemView.findViewById<TextView>(R.id.btn_drag).setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    mItemTouchHelper?.startDrag(holder)
                    return@setOnTouchListener true
                }
                false
            }
        }

        inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val icon: android.widget.ImageView = v.findViewById(R.id.app_icon)
            val label: TextView = v.findViewById(R.id.app_label)
            val check: CheckBox = v.findViewById(R.id.item_check)
        }

        inner class DragCallback : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int = androidx.recyclerview.widget.ItemTouchHelper.Callback.makeMovementFlags(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0)

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}

            override fun onSelectedChanged(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?, action: Int) {
                super.onSelectedChanged(vh, action)
                // 拖起浮起：放大 + 抬升，落位还原
                if (action == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)
                        ?.translationZ(12f)?.setDuration(80)?.start()
                }
            }

            override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
                if (mDragDirty) {
                    mDragDirty = false
                    Store.saveDrawerOrder(this@SettingsActivity, mAppOrder)
                }
            }
        }

    }

    private var mOrderAdapter: OrderAdapter? = null
    private var mItemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

    private fun bindDrawerLooks() {
        val box = findViewById<LinearLayout>(R.id.box_drawer_seeks)
        box.removeAllViews()
        bindSeek(box, "窗口宽度", KEY_DRAWER_WIDTH_PCT, 75, 50, 95, unit = "%")
        bindSeek(box, "窗口高度", KEY_DRAWER_HEIGHT_PCT, 75, 50, 95, unit = "%")
        bindSeek(box, "图标大小", KEY_DRAWER_ICON_SIZE, 64, 40, 120)
        bindSeek(box, "间距", KEY_DRAWER_ICON_GAP, 8, 0, 40)
        bindSeek(box, "字体大小", KEY_DRAWER_LABEL_SIZE, 17, 12, 40)
    }

    private fun bindSpeedTab() {
        val box = findViewById<LinearLayout>(R.id.box_all_settings)

        // 导航全部行序（车速 + 行序）
        val naviDefaultOrder = NaviPanelDelegate.DEFAULT_NAVI_ORDER
        val naviSaved = prefs().getString(KEY_NAVI_ORDER, naviDefaultOrder)!!
        mNaviOrder.clear()
        mNaviOrder.addAll(naviSaved.split(",").filter { it.isNotBlank() })

        val cruiseDefaultOrder = NaviPanelDelegate.DEFAULT_CRUISE_ORDER
        val cruiseSaved = prefs().getString(KEY_CRUISE_ORDER, cruiseDefaultOrder)!!
        mCruiseOrder.clear()
        mCruiseOrder.addAll(cruiseSaved.split(",").filter { it.isNotBlank() })

        rebuildSpeedPanel(box)
    }

    /**
     * 整屏渲染：[ 导航 ] → 全部设置项（车速+行序） → [ 巡航 ] → 全部设置项（车速+行序）。
     * 两个分区各挂一个 RecyclerView：长按行（或 ≡ 手柄）拖拽排序。
     */
    private fun rebuildSpeedPanel(box: LinearLayout) {
        box.removeAllViews()
        // ── 导航区 ──
        renderSectionHeader(box, "导航")
        addSpeedOrderList(box, naviAllItems, mNaviOrder, KEY_NAVI_ORDER)
        // ── 巡航区 ──
        renderSectionHeader(box, "巡航")
        addSpeedOrderList(box, cruiseAllItems, mCruiseOrder, KEY_CRUISE_ORDER)
    }

    /** 分区行列表：RecyclerView + ItemTouchHelper 长按拖拽（与"应用"tab 交互一致） */
    private fun addSpeedOrderList(
        box: LinearLayout,
        all: List<SettingItem>,
        order: ArrayList<String>,
        persistKey: String
    ) {
        val rv = WrapRecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val adapter = SpeedOrderAdapter(all, order, persistKey)
        rv.adapter = adapter
        adapter.touchHelper = androidx.recyclerview.widget.ItemTouchHelper(adapter.DragCallback())
        adapter.touchHelper!!.attachToRecyclerView(rv)
        box.addView(rv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(2) })
    }

    /** 分区之间仅留分隔线（导航/巡航两个列表的视觉分隔，标题已按要求去掉） */
    private fun renderSectionHeader(box: LinearLayout, title: String) {
        if (box.childCount > 0) {
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply { topMargin = dpToPx(12) }
                setBackgroundColor(resources.getColor(R.color.divider, theme))
            }
            box.addView(div)
        }
    }

    /**
     * 车速区行适配器：label + 显示 checkbox + 字号 picker，长按行（或 ≡ 手柄）拖拽排序。
     * rows 顺序 = order 列表顺序；拖动交换同步 order，松手持久化 [persistKey]。
     */
    private inner class SpeedOrderAdapter(
        private val all: List<SettingItem>,
        private val order: ArrayList<String>,
        private val persistKey: String
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SpeedOrderAdapter.VH>() {

        /** 各分区自己的 ItemTouchHelper（onBind 手柄 startDrag 用） */
        var touchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

        private val rows = ArrayList<SettingItem>()
        private var mDragDirty = false

        init {
            for (key in order) all.firstOrNull { it.key == key }?.let { rows.add(it) }
        }

        fun move(from: Int, to: Int) {
            java.util.Collections.swap(rows, from, to)
            notifyItemMoved(from, to)
            order.clear()
            order.addAll(rows.map { it.key })
            mDragDirty = true
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_speed_setting, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = rows[position]
            holder.label.text = item.label

            val check = holder.check
            if (item.showKey.isNotEmpty()) {
                check.visibility = View.VISIBLE
                check.setOnCheckedChangeListener(null)
                check.isChecked = prefs().getBoolean(item.showKey, item.showDefault)
                check.setOnCheckedChangeListener { _, checked ->
                    prefs().edit().putBoolean(item.showKey, checked).apply()
                }
            } else {
                check.visibility = View.INVISIBLE
            }

            // 车速卡字号上限 150（与桌面坐标系下像素硬限制一致），其余 250
            holder.picker.setRange(0, if (item.fontKey in FONT_RANGE_SMALL) 150 else 250, 1)
            holder.picker.setValue(prefs().getInt(item.fontKey, item.fontDefault))
            holder.picker.setOnValueChangeListener { _, newVal ->
                prefs().edit().putInt(item.fontKey, newVal).apply()
            }

            holder.drag.setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                    return@setOnTouchListener true
                }
                false
            }
        }

        inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.item_label)
            val check: CheckBox = v.findViewById(R.id.item_check)
            val picker: NumberPickerView = v.findViewById(R.id.picker_font)
            val drag: TextView = v.findViewById(R.id.btn_drag)
        }

        inner class DragCallback : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int = androidx.recyclerview.widget.ItemTouchHelper.Callback.makeMovementFlags(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0)

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}

            override fun onSelectedChanged(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?, action: Int) {
                super.onSelectedChanged(vh, action)
                // 拖起浮起：放大 + 抬升，落位还原
                if (action == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)
                        ?.translationZ(12f)?.setDuration(80)?.start()
                }
            }

            override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
                if (mDragDirty) {
                    mDragDirty = false
                    saveOrder(persistKey, order)
                }
            }
        }
    }

    private fun saveOrder(key: String, list: ArrayList<String>) {
        prefs().edit().putString(key, list.joinToString(",")).apply()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun bindMusicTab() {
        val box = findViewById<LinearLayout>(R.id.box_music_seeks)
        bindSeek(box, "歌曲名", KEY_TS_MUSIC_TITLE, 24, 14, 40)
        bindSeek(box, "歌手名", KEY_TS_MUSIC_ARTIST, 15, 10, 28)
        bindSeek(box, "进度时间", KEY_TS_MUSIC_TIME, 15, 10, 28)

        bindCheck(R.id.cb_music_title, KEY_SHOW_MUSIC_TITLE)
        bindCheck(R.id.cb_music_artist, KEY_SHOW_MUSIC_ARTIST)
        bindCheck(R.id.cb_music_time, KEY_SHOW_MUSIC_TIME)
        bindCheck(R.id.cb_music_bar, KEY_SHOW_MUSIC_BAR)

        refreshMusicAppRow()
        findViewById<Button>(R.id.btn_pick_music_app).setOnClickListener { pickMusicApp() }
    }

    private fun refreshMusicAppRow() {
        val iv = findViewById<android.widget.ImageView>(R.id.iv_music_app_icon)
        val tv = findViewById<TextView>(R.id.tv_music_app_name)
        val btn = findViewById<Button>(R.id.btn_pick_music_app)
        val id = prefs().getString("music_app_pkg", null)
        if (id.isNullOrEmpty()) {
            iv.setImageDrawable(null)
            tv.text = "未绑定"
            btn.text = "选择"
        } else {
            val icon = Store.normalizedIcon(this, id) ?: try { packageManager.getApplicationIcon(Store.pkgOf(id)) } catch (_: Exception) { null }
            if (icon != null) iv.setImageDrawable(icon) else iv.setImageDrawable(null)
            tv.text = Store.label(this, id)
            btn.text = "更换"
        }
    }

    private fun pickMusicApp() {
        val themed = HoloPopup.themedContext(this)
        val list = android.widget.ListView(themed)
        val popup = HoloPopup.showWithWidth(this, HoloPopup.titledPanel(themed, "选择音乐应用", list), HoloPopup.WIDTH_SMALL)
        val entries = AppQuery.launcherEntries(this, null)
        SharedExecutor.io().execute {
            val adapter = object : android.widget.BaseAdapter() {
                val labels = entries.map { Store.label(this@SettingsActivity, AppQuery.appId(it)) }
                val icons = entries.map { Store.normalizedIcon(this@SettingsActivity, AppQuery.appId(it)) }
                override fun getCount() = entries.size
                override fun getItem(p: Int) = entries[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = cv ?: LayoutInflater.from(this@SettingsActivity).inflate(R.layout.item_app, parent, false)
                    v.findViewById<android.widget.ImageView>(R.id.app_icon).setImageDrawable(icons[pos])
                    v.findViewById<TextView>(R.id.app_name).text = labels[pos]
                    return v
                }
            }
            list.post { if (!isDestroyed && !isFinishing) list.adapter = adapter }
        }
        list.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
            popup.dismiss()
            val ri = entries[pos]
            val pkgCls = AppQuery.appId(ri)
            prefs().edit().putString("music_app_pkg", pkgCls).apply()
            refreshMusicAppRow()
        }
    }

    private fun bindTimeTab() {
        val box = findViewById<LinearLayout>(R.id.box_time_seeks)
        box.removeAllViews()
        bindSeek(box, "时间字号", KEY_TS_TIME, 28, 14, 60)
        val tvFormat = findViewById<TextView>(R.id.tv_time_format)
        fun refreshFormatLabel() {
            val fmt = prefs().getInt(KEY_TIME_FORMAT, 1)
            val label = when (fmt) {
                0 -> "HH:mm"
                1 -> "HH:mm:ss"
                2 -> "yyyy-MM-dd HH:mm"
                3 -> "yyyy-MM-dd\\nHH:mm:ss (换行)"
                else -> "HH:mm:ss"
            }
            tvFormat.text = "时间格式：$label（点击切换）"
        }
        refreshFormatLabel()
        tvFormat.setOnClickListener {
            val cur = prefs().getInt(KEY_TIME_FORMAT, 1)
            val next = (cur + 1) % 4
            prefs().edit().putInt(KEY_TIME_FORMAT, next).apply()
            refreshFormatLabel()
        }
    }

    private fun prefs(): SharedPreferences = Prefs.of(this)

    private fun bindCheck(viewId: Int, key: String) {
        val cb = findViewById<CheckBox>(viewId)
        cb.isChecked = prefs().getBoolean(key, SHOW_DEFAULTS[key] ?: true)
        cb.setOnCheckedChangeListener { _, isChecked ->
            prefs().edit().putBoolean(key, isChecked).apply()
        }
    }

    /** px 滑条行：范围 [min,max]，拖动即写 SP，右侧实时显示当前值 */
    private fun bindSeek(box: LinearLayout, name: String, key: String, def: Int, min: Int, max: Int, unit: String = "px") {
        val row = LayoutInflater.from(this).inflate(R.layout.item_seek_row, box, false)
        row.findViewById<TextView>(R.id.seek_name).text = name
        val valTv = row.findViewById<TextView>(R.id.seek_value)
        val picker = row.findViewById<NumberPickerView>(R.id.seek_picker)
        val cur = prefs().getInt(key, def)
        picker.setRange(min, max, 1)
        picker.setValue(cur)
        valTv.text = "$cur$unit"
        picker.setOnValueChangeListener { _, newVal ->
            valTv.text = "$newVal$unit"
            prefs().edit().putInt(key, newVal).apply()
        }
        box.addView(row)
    }

    // 颜色设置页已移除：主题色改由 values/colors.xml + values-night/colors.xml 自动切换

    private fun bindGeneralTab() {
        bindCheck(R.id.cb_home_direct_drawer, KEY_HOME_DIRECT_APP_DRAWER)
        val box = findViewById<LinearLayout>(R.id.box_general_seeks)
        bindSeek(box, "桌面拉起延迟", KEY_PIP_START_DELAY, 250, 0, 1000, unit = "毫秒")
        bindSeek(box, "VD拉起延迟", KEY_VD_LAUNCH_DELAY, 250, 0, 1000, unit = "毫秒")
        mTvUpdateStatus = findViewById(R.id.tv_update_status)
        findViewById<TextView>(R.id.tv_version_info).text = buildVersionInfo()
        val btn = findViewById<Button>(R.id.btn_check_update)
        val updater = UpdateChecker(this, object : UpdateChecker.Listener {
            override fun onUpdateStart() {
                mTvUpdateStatus?.text = "正在检查更新…"
            }
            override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
                mTvUpdateStatus?.text = "发现新版本 v${info.versionName} (code=${info.versionCode})，等待确认"
                try {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("发现新版本 v${info.versionName}")
                        .setMessage("是否立即下载并安装？")
                        .setPositiveButton("立即更新") { _, _ ->
                            mTvUpdateStatus?.text = "开始下载…"
                            mUpdater?.confirmUpdate()
                        }
                        .setNegativeButton("稍后") { _, _ ->
                            mTvUpdateStatus?.text = "已取消，可重新检查更新"
                        }
                        .show()
                } catch (e: Exception) {
                    mTvUpdateStatus?.text = "弹窗失败：${e.message}"
                }
            }
            override fun onUpToDate() {
                mTvUpdateStatus?.text = "已是最新版本"
            }
            override fun onProgress(percent: Int) {
                mTvUpdateStatus?.text = "下载中 $percent%"
            }
            override fun onError(message: String) {
                mTvUpdateStatus?.text = message
            }
        })
        mUpdater = updater
        btn.setOnClickListener { updater.checkManually() }
    }

    /**
     * 构造"当前版本"信息字符串（用于通用 tab 顶部 TextView）。
     * 内容：
     *  - versionName（来自 packageInfo）
     *  - versionCode（构建 epoch 秒数；更新检查以此为唯一对比基准）
     *  - 构建时间（北京时间，Asia/Shanghai，从 BuildConfig.BUILD_TIME_EPOCH 读）
     */
    private fun buildVersionInfo(): String {
        val (vn, vc) = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val vname = pi.versionName ?: "?"
            val vcode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            vname to vcode
        } catch (e: PackageManager.NameNotFoundException) {
            "?" to 0L
        }
        val buildEpoch = BuildConfig.BUILD_TIME_EPOCH
        val cstFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val buildTimeCst = cstFmt.format(Date(buildEpoch * 1000L))
        return "当前版本：v$vn  (code=$vc)  构建：$buildTimeCst (北京时间)"
    }
}
