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

        // ── 车速卡显示开关 ────────────────────
        const val KEY_SHOW_SPEED = "show_speed"
        const val KEY_SHOW_KMH = "show_kmh"
        const val KEY_SHOW_LIMIT = "show_limit"
        const val KEY_SHOW_TRAFFIC = "show_traffic"
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
        const val KEY_SHOW_CRUISE_ETA_TEXT = "show_cruise_eta_text"
        const val KEY_SHOW_CRUISE_LIGHT_COUNT = "show_cruise_light_count"
        const val KEY_SHOW_CRUISE_DIRECTION = "show_cruise_direction"
        const val KEY_SHOW_CRUISE_ALERT = "show_cruise_alert"
        const val KEY_SHOW_MUSIC_TITLE = "show_music_title"
        const val KEY_SHOW_MUSIC_ARTIST = "show_music_artist"
        const val KEY_SHOW_MUSIC_TIME = "show_music_time"
        const val KEY_SHOW_MUSIC_BAR = "show_music_bar"

        /** bool 开关全集（LauncherActivity.loadSettings 按 key=true 缺省快照） */
        val SHOW_KEYS = arrayOf(
            KEY_SHOW_SPEED, KEY_SHOW_KMH, KEY_SHOW_LIMIT, KEY_SHOW_TRAFFIC,
            KEY_SHOW_NAVI_TURN, KEY_SHOW_NAVI_ROAD, KEY_SHOW_NAVI_DEST,
            KEY_SHOW_NAVI_ETA, KEY_SHOW_NAVI_ETA_TEXT, KEY_SHOW_NAVI_LIGHT_COUNT,
            KEY_SHOW_NAVI_EXIT, KEY_SHOW_NAVI_DIRECTION, KEY_SHOW_NAVI_ALERT,
            KEY_SHOW_CRUISE_ROAD, KEY_SHOW_CRUISE_ETA_TEXT, KEY_SHOW_CRUISE_LIGHT_COUNT,
            KEY_SHOW_CRUISE_DIRECTION, KEY_SHOW_CRUISE_ALERT,
            KEY_SHOW_MUSIC_TITLE, KEY_SHOW_MUSIC_ARTIST, KEY_SHOW_MUSIC_TIME,
            KEY_SHOW_MUSIC_BAR
        )

        // ── 字号/尺寸（px，int）──────────────────
        const val KEY_TS_SPEED = "ts_speed"
        const val KEY_TS_KMH = "ts_kmh"
        const val KEY_TS_LIMIT = "ts_limit"
        const val KEY_TS_TRAFFIC_SEC = "ts_traffic_sec"
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
        const val KEY_TS_CRUISE_ETA_TEXT = "ts_cruise_eta_text"
        const val KEY_TS_CRUISE_LIGHT_COUNT = "ts_cruise_light_count"
        const val KEY_TS_CRUISE_DIRECTION = "ts_cruise_direction"
        const val KEY_TS_CRUISE_ALERT = "ts_cruise_alert"
        // 音乐
        const val KEY_TS_MUSIC_TITLE = "ts_music_title"
        const val KEY_TS_MUSIC_ARTIST = "ts_music_artist"
        const val KEY_TS_MUSIC_TIME = "ts_music_time"

        /** int 尺寸快照表（key 顺序与默认值一一对应） */
        val INT_KEYS = arrayOf(
            KEY_PAGE_PADDING, KEY_CARD_GAP,
            KEY_SPEED_CARD_W, KEY_MUSIC_CARD_H,
            KEY_TS_SPEED, KEY_TS_KMH, KEY_TS_LIMIT,
            KEY_TS_TRAFFIC_SEC,
            KEY_TS_NAVI_TURN, KEY_TS_NAVI_ROAD, KEY_TS_NAVI_DEST,
            KEY_TS_NAVI_ETA, KEY_TS_NAVI_ETA_TEXT, KEY_TS_NAVI_LIGHT_COUNT,
            KEY_TS_NAVI_EXIT, KEY_TS_NAVI_DIRECTION, KEY_TS_NAVI_ALERT,
            KEY_TS_CRUISE_ROAD, KEY_TS_CRUISE_ETA_TEXT, KEY_TS_CRUISE_LIGHT_COUNT,
            KEY_TS_CRUISE_DIRECTION, KEY_TS_CRUISE_ALERT,
            KEY_TS_MUSIC_TITLE, KEY_TS_MUSIC_ARTIST, KEY_TS_MUSIC_TIME
        )

        val INT_DEFAULTS = intArrayOf(
            0, 0, 260, 200,
            110, 20, 17, 20,
            36, 26, 15, 17, 17, 17, 17, 17, 17,
            26, 17, 17, 17, 17,
            24, 15, 15
        )

        // ── 行序键（持久化到 SP）─────────────────
        const val KEY_NAVI_ORDER = "navi_row_order"
        const val KEY_CRUISE_ORDER = "cruise_row_order"
        // 历史遗留键，保留只读兼容（新版不再写入）
        const val KEY_ALL_ROW_ORDER = "all_row_order"

        // ── ADB 调试入口 ───────────────────────
        const val KEY_ADB_DEBUG = "adb_debug_enabled"
    }

    private lateinit var mTabs: Array<TextView>
    private lateinit var mPanels: Array<View>
    private lateinit var mTabActiveBg: GradientDrawable
    /** 导航行序（持久化 navi_row_order） */
    private val mNaviOrder: ArrayList<String> = ArrayList()
    /** 巡航行序（持久化 cruise_row_order） */
    private val mCruiseOrder: ArrayList<String> = ArrayList()
    private var mUpdater: UpdateChecker? = null
    private var mTvUpdateStatus: TextView? = null

    /** 设置页导航分区子标题 */
    private var mTvNaviHeader: TextView? = null
    /** 设置页巡航分区子标题 */
    private var mTvCruiseHeader: TextView? = null

    // ── 设置项定义：key, 显示名, 字号 key, 默认字号, 显示开关 key（空=无开关）, 是否可排序 ──
    private data class SettingItem(
        val key: String,
        val label: String,
        val fontKey: String,
        val fontDefault: Int,
        val showKey: String,
        val orderable: Boolean
    )

    /** 车速卡 4 个字号 key：范围 [0,150]；其余 [0,250] */
    private val FONT_RANGE_SMALL = setOf(
        KEY_TS_SPEED, KEY_TS_KMH, KEY_TS_LIMIT, KEY_TS_TRAFFIC_SEC
    )

    /** 固定行（车速卡顶部）：无排序控件 */
    private val fixedItems: List<SettingItem> = listOf(
        SettingItem("speed",   "[车速]车速数字",     KEY_TS_SPEED,       110, KEY_SHOW_SPEED,      false),
        SettingItem("km",      "[车速]km/h 单位",   KEY_TS_KMH,         20,  KEY_SHOW_KMH,        false),
        SettingItem("limit",   "[车速]道路限速",    KEY_TS_LIMIT,       17,  KEY_SHOW_LIMIT,      false),
        SettingItem("traffic", "[车速]红绿灯倒计时", KEY_TS_TRAFFIC_SEC, 36,  KEY_SHOW_TRAFFIC,    false)
    )

    /**
     * 导航模式行序设置项。**每条都有独立的 showKey**，允许关闭某个导航字段
     * （如关闭 navi_exit 后即使高德广播带 EXIT_NAME_INFO 也不渲染）。
     */
    private val naviItems: List<SettingItem> = listOf(
        SettingItem("navi_turn",       "转向图标与距离", KEY_TS_NAVI_TURN,         36, KEY_SHOW_NAVI_TURN,        true),
        SettingItem("navi_road",       "路名",          KEY_TS_NAVI_ROAD,         26, KEY_SHOW_NAVI_ROAD,        true),
        SettingItem("navi_dest",       "终点名称",      KEY_TS_NAVI_DEST,         15, KEY_SHOW_NAVI_DEST,        true),
        SettingItem("navi_eta",        "剩余距离时间",  KEY_TS_NAVI_ETA,          17, KEY_SHOW_NAVI_ETA,         true),
        SettingItem("navi_eta_text",   "ETA预计到达",   KEY_TS_NAVI_ETA_TEXT,     17, KEY_SHOW_NAVI_ETA_TEXT,    true),
        SettingItem("navi_light_count","剩余红绿灯数",  KEY_TS_NAVI_LIGHT_COUNT,  17, KEY_SHOW_NAVI_LIGHT_COUNT, true),
        SettingItem("navi_exit",       "出口信息",      KEY_TS_NAVI_EXIT,         17, KEY_SHOW_NAVI_EXIT,        true),
        SettingItem("navi_direction",  "车头方向",      KEY_TS_NAVI_DIRECTION,    17, KEY_SHOW_NAVI_DIRECTION,   true),
        SettingItem("navi_alert",      "电子眼/服务区", KEY_TS_NAVI_ALERT,        17, KEY_SHOW_NAVI_ALERT,       true)
    )

    /**
     * 巡航模式行序设置项。**每条都有独立的 showKey**；导航专用字段（转向/终点/出口）
     * 不出现在巡航里——巡航不渲染。
     */
    private val cruiseItems: List<SettingItem> = listOf(
        SettingItem("cruise_road",        "当前路名",      KEY_TS_CRUISE_ROAD,         26, KEY_SHOW_CRUISE_ROAD,        true),
        SettingItem("cruise_eta_text",    "ETA预计到达",   KEY_TS_CRUISE_ETA_TEXT,     17, KEY_SHOW_CRUISE_ETA_TEXT,    true),
        SettingItem("cruise_light_count", "剩余红绿灯数",  KEY_TS_CRUISE_LIGHT_COUNT,  17, KEY_SHOW_CRUISE_LIGHT_COUNT, true),
        SettingItem("cruise_direction",   "车头方向",      KEY_TS_CRUISE_DIRECTION,    17, KEY_SHOW_CRUISE_DIRECTION,   true),
        SettingItem("cruise_alert",       "电子眼/服务区", KEY_TS_CRUISE_ALERT,        17, KEY_SHOW_CRUISE_ALERT,       true)
    )

    /** 向后兼容：settingItems 旧接口，给 item_speed_setting 渲染器统一用 */
    private val allItems: List<SettingItem> by lazy {
        fixedItems + naviItems + cruiseItems
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
            findViewById(R.id.tab_general)
        )
        mPanels = arrayOf(
            findViewById(R.id.panel_layout),
            findViewById(R.id.panel_speed),
            findViewById(R.id.panel_music),
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
        val primary = resources.getColor(R.color.onSurface, theme)
        val secondary = resources.getColor(R.color.onSurfaceVariant, theme)
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
        bindSeek(box, "页面边距", KEY_PAGE_PADDING, 0, 0, 40)
        bindSeek(box, "部件间距", KEY_CARD_GAP, 0, 0, 40)
        bindSeek(box, "车速卡宽度", KEY_SPEED_CARD_W, 260, 220, 400)
        bindSeek(box, "音乐卡高度", KEY_MUSIC_CARD_H, 200, 140, 300)
    }

    private fun bindSpeedTab() {
        val box = findViewById<LinearLayout>(R.id.box_all_settings)

        // 导航行序（持久化 navi_row_order）
        val naviSaved = prefs().getString(
            KEY_NAVI_ORDER,
            "navi_turn,navi_road,navi_dest,navi_eta,navi_eta_text,navi_light_count,navi_exit,navi_direction,navi_alert"
        )!!
        mNaviOrder.clear()
        mNaviOrder.addAll(naviSaved.split(",").filter { it.isNotBlank() })

        // 巡航行序（持久化 cruise_row_order）
        val cruiseSaved = prefs().getString(
            KEY_CRUISE_ORDER,
            "cruise_road,cruise_eta_text,cruise_light_count,cruise_direction,cruise_alert"
        )!!
        mCruiseOrder.clear()
        mCruiseOrder.addAll(cruiseSaved.split(",").filter { it.isNotBlank() })

        rebuildSpeedPanel(box)
    }

    /**
     * 整屏渲染：固定行 → [导航] 子标题 → 导航排序行 → [巡航] 子标题 → 巡航排序行。
     * 用 section label 视觉切分两个区域，互不干扰。
     */
    private fun rebuildSpeedPanel(box: LinearLayout) {
        box.removeAllViews()
        // 固定行（车速卡顶部）
        for (item in fixedItems) {
            renderSettingRow(box, item, null)
        }
        // 导航分区
        renderSectionHeader(box, "[ 导航 ]")
        for (i in mNaviOrder.indices) {
            val key = mNaviOrder[i]
            val item = naviItems.firstOrNull { it.key == key } ?: continue
            renderSettingRow(box, item, i, isNavi = true)
        }
        // 巡航分区
        renderSectionHeader(box, "[ 巡航 ]")
        for (i in mCruiseOrder.indices) {
            val key = mCruiseOrder[i]
            val item = cruiseItems.firstOrNull { it.key == key } ?: continue
            renderSettingRow(box, item, i, isNavi = false)
        }
    }

    /** 渲染分区子标题：粗体 + 上分隔线 + 内边距 */
    private fun renderSectionHeader(box: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(resources.getColor(R.color.onSurface, theme))
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
            check.isChecked = prefs().getBoolean(item.showKey, true)
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

    private fun prefs(): SharedPreferences = Prefs.of(this)

    private fun bindCheck(viewId: Int, key: String) {
        val cb = findViewById<CheckBox>(viewId)
        cb.isChecked = prefs().getBoolean(key, true)
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
