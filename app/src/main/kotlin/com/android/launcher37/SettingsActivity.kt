package com.android.launcher37

import android.app.Activity
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

        /** bool 开关全集（LauncherActivity.loadSettings 按 key=true 缺省快照） */
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
            KEY_SPEED_CARD_W, KEY_MUSIC_CARD_H, KEY_DOCK_HEIGHT,
            KEY_TS_NAVI_SPEED, KEY_TS_NAVI_KMH, KEY_TS_NAVI_LIMIT, KEY_TS_NAVI_TRAFFIC_SEC,
            KEY_TS_CRUISE_SPEED, KEY_TS_CRUISE_KMH, KEY_TS_CRUISE_LIMIT, KEY_TS_CRUISE_TRAFFIC_SEC,
            KEY_TS_NAVI_TURN, KEY_TS_NAVI_ROAD, KEY_TS_NAVI_DEST,
            KEY_TS_NAVI_ETA, KEY_TS_NAVI_ETA_TEXT, KEY_TS_NAVI_LIGHT_COUNT,
            KEY_TS_NAVI_EXIT, KEY_TS_NAVI_DIRECTION, KEY_TS_NAVI_ALERT,
            KEY_TS_CRUISE_ROAD, KEY_TS_CRUISE_DIRECTION, KEY_TS_CRUISE_ALERT,
            KEY_TS_MUSIC_TITLE, KEY_TS_MUSIC_ARTIST, KEY_TS_MUSIC_TIME,
            KEY_TIME_CARD_H, KEY_TS_TIME, KEY_TIME_FORMAT
        )

        val INT_DEFAULTS = intArrayOf(
            10, 10, 260, 180, 80,
            110, 20, 17, 36,
            110, 20, 17, 36,
            36, 26, 15, 17, 17, 17, 17, 17, 17,
            26, 17, 17,
            24, 15, 15,
            60, 28, 1
        )

        // ── 行序键（持久化到 SP）─────────────────
        const val KEY_NAVI_ORDER = "navi_row_order"
        const val KEY_CRUISE_ORDER = "cruise_row_order"

        // ── 通用 ───────────────────────
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar"

        // ── ADB 调试入口 ───────────────────────
        const val KEY_ADB_DEBUG = "adb_debug_enabled"
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

    private val allItems: List<SettingItem> by lazy {
        naviAllItems + cruiseAllItems
    }

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
            findViewById(R.id.tab_general)
        )
        mPanels = arrayOf(
            findViewById(R.id.panel_layout),
            findViewById(R.id.panel_speed),
            findViewById(R.id.panel_music),
            findViewById(R.id.panel_time),
            findViewById(R.id.panel_dock),
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
     * 启动 LauncherActivity —— 进程保留，AdbDebug / PipService 不受影响。`onCreate` 会把
     * 5 个 delegate 全部重新构造，新 SP 字段自然生效。
     */
    private fun restartLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
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
    }

    private fun bindSpeedTab() {
        val box = findViewById<LinearLayout>(R.id.box_all_settings)

        // 导航全部行序（车速 + 行序）
        val naviDefaultOrder = "speed,speed_unit,limit,traffic,navi_turn,navi_road,navi_dest,navi_eta,navi_eta_text,navi_light_count,navi_exit,navi_direction,navi_alert"
        val naviSaved = prefs().getString(KEY_NAVI_ORDER, naviDefaultOrder)!!
        mNaviOrder.clear()
        mNaviOrder.addAll(naviSaved.split(",").filter { it.isNotBlank() })

        // 巡航全部行序（车速 + 行序）
        val cruiseDefaultOrder = "speed,speed_unit,limit,traffic,cruise_road,cruise_direction,cruise_alert"
        val cruiseSaved = prefs().getString(KEY_CRUISE_ORDER, cruiseDefaultOrder)!!
        mCruiseOrder.clear()
        mCruiseOrder.addAll(cruiseSaved.split(",").filter { it.isNotBlank() })

        rebuildSpeedPanel(box)
    }

    /**
     * 整屏渲染：[ 导航 ] → 全部设置项（车速+行序） → [ 巡航 ] → 全部设置项（车速+行序）。
     */
    private fun rebuildSpeedPanel(box: LinearLayout) {
        box.removeAllViews()
        // ── 导航区 ──
        renderSectionHeader(box, "导航")
        for (i in mNaviOrder.indices) {
            val key = mNaviOrder[i]
            val item = naviAllItems.firstOrNull { it.key == key } ?: continue
            renderSettingRow(box, item, i, isNavi = true)
        }
        // ── 巡航区 ──
        renderSectionHeader(box, "巡航")
        for (i in mCruiseOrder.indices) {
            val key = mCruiseOrder[i]
            val item = cruiseAllItems.firstOrNull { it.key == key } ?: continue
            renderSettingRow(box, item, i, isNavi = false)
        }
    }

    /** 渲染分区子标题：统一样式 23px 粗体 + 上分隔线 1px divider */
    private fun renderSectionHeader(box: LinearLayout, title: String) {
        if (box.childCount > 0) {
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply { topMargin = dpToPx(24) }
                setBackgroundColor(resources.getColor(R.color.divider, theme))
            }
            box.addView(div)
        }
        val tv = TextView(this).apply {
            text = title
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpToPx(23).toFloat())
            setTextColor(resources.getColor(R.color.foreground, theme))
            paint.isFakeBoldText = true
            setPadding(0, dpToPx(16), 0, dpToPx(8))
        }
        box.addView(tv)
    }

    /**
     * 渲染一行设置：label + CheckBox(显隐) + 字号 picker +
     * 可选上下箭头（[orderablePos] != null 时显示）。
     *
     * @param isNavi 当前 row 所属分区；用于上下移动时选择 [mNaviOrder] / [mCruiseOrder]
     */
    private fun renderSettingRow(box: LinearLayout, item: SettingItem, orderablePos: Int?, isNavi: Boolean = false) {
        val view = layoutInflater.inflate(R.layout.item_speed_setting, box, false)
        view.findViewById<TextView>(R.id.item_label).text = item.label

        val check = view.findViewById<CheckBox>(R.id.item_check)
        if (item.showKey.isNotEmpty()) {
            check.visibility = View.VISIBLE
            check.isChecked = prefs().getBoolean(item.showKey, item.showDefault)
            check.setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean(item.showKey, checked).apply()
            }
        } else {
            check.visibility = View.INVISIBLE
        }

        val pickerFont = view.findViewById<NumberPickerView>(R.id.picker_font)
        val curFont = prefs().getInt(item.fontKey, item.fontDefault)
        // 车速卡字号上限 150（与桌面坐标系下像素硬限制一致），其余 250
        pickerFont.setRange(0, if (item.fontKey in FONT_RANGE_SMALL) 150 else 250, 1)
        pickerFont.setValue(curFont)
        pickerFont.setOnValueChangeListener { _, newVal ->
            prefs().edit().putInt(item.fontKey, newVal).apply()
        }

        if (orderablePos != null) {
            val pos = orderablePos
            val orderList = if (isNavi) mNaviOrder else mCruiseOrder
            val persistKey = if (isNavi) KEY_NAVI_ORDER else KEY_CRUISE_ORDER
            view.findViewById<Button>(R.id.btn_up).setOnClickListener {
                if (pos > 0) {
                    java.util.Collections.swap(orderList, pos, pos - 1)
                    saveOrder(persistKey, orderList)
                    rebuildSpeedPanel(box)
                }
            }
            view.findViewById<Button>(R.id.btn_down).setOnClickListener {
                if (pos < orderList.size - 1) {
                    java.util.Collections.swap(orderList, pos, pos + 1)
                    saveOrder(persistKey, orderList)
                    rebuildSpeedPanel(box)
                }
            }
        } else {
            view.findViewById<View>(R.id.btn_up).visibility = View.GONE
            view.findViewById<View>(R.id.btn_down).visibility = View.GONE
        }
        box.addView(view)
    }

    private fun saveOrder(key: String, list: ArrayList<String>) {
        val sb = StringBuilder()
        for (i in list.indices) {
            if (i > 0) sb.append(",")
            sb.append(list[i])
        }
        prefs().edit().putString(key, sb.toString()).apply()
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
    private fun bindSeek(box: LinearLayout, name: String, key: String, def: Int, min: Int, max: Int) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_seek_row, box, false)
        row.findViewById<TextView>(R.id.seek_name).text = name
        val valTv = row.findViewById<TextView>(R.id.seek_value)
        val picker = row.findViewById<NumberPickerView>(R.id.seek_picker)
        val cur = prefs().getInt(key, def)
        picker.setRange(min, max, 1)
        picker.setValue(cur)
        valTv.text = "${cur}px"
        picker.setOnValueChangeListener { _, newVal ->
            valTv.text = "${newVal}px"
            prefs().edit().putInt(key, newVal).apply()
        }
        box.addView(row)
    }

    // 颜色设置页已移除：主题色改由 values/colors.xml + values-night/colors.xml 自动切换

    private fun bindGeneralTab() {
        bindAdbDebugToggle()
        mTvUpdateStatus = findViewById(R.id.tv_update_status)
        findViewById<TextView>(R.id.tv_version_info).text = buildVersionInfo()
        val btn = findViewById<Button>(R.id.btn_check_update)
        val updater = UpdateChecker(this, object : UpdateChecker.Listener {
            override fun onUpdateStart() {
                mTvUpdateStatus?.text = "正在检查更新…"
            }
            override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
                mTvUpdateStatus?.text = "发现新版本 v${info.versionName} (code=${info.versionCode})，开始下载…"
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
     * 绑定 ADB 调试入口状态行。仅展示当前开关状态（默认开启）：
     * - 已开启：监听 0.0.0.0:10837（同网段可直连，无鉴权）
     * - 已关闭（修改后下次进程重启生效）
     *
     * CI release 走 -PminifyRelease=true 时，整段方法被 R8 视为死代码消除，
     * 状态文字保持 layout 默认 `tv_adb_debug_status` 的「不支持 debug」。
     */
    private fun bindAdbDebugToggle() {
        if (!BuildConfig.ADB_DEBUG) return
        val status = findViewById<TextView>(R.id.tv_adb_debug_status)
        status.text = if (prefs().getBoolean(KEY_ADB_DEBUG, true)) {
            "已开启：监听 0.0.0.0:10837（同网段可直连，无鉴权）"
        } else {
            "已关闭（修改后下次进程重启生效）"
        }
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
