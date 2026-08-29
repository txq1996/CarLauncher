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
 * 桌面设置：左侧选项卡（布局/车速/音乐/底栏/通用）+ 右侧内容面板。
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
        const val KEY_SHOW_NAVI_ALERT = "show_navi_alert"
        const val KEY_SHOW_CRUISE_ALERT = "show_cruise_alert"
        const val KEY_SHOW_MUSIC_TITLE = "show_music_title"
        const val KEY_SHOW_MUSIC_ARTIST = "show_music_artist"
        const val KEY_SHOW_MUSIC_TIME = "show_music_time"
        const val KEY_SHOW_MUSIC_BAR = "show_music_bar"

        /** bool 开关全集（LauncherActivity.loadSettings 按 key=true 缺省快照） */
        val SHOW_KEYS = arrayOf(
            KEY_SHOW_SPEED, KEY_SHOW_KMH, KEY_SHOW_LIMIT, KEY_SHOW_TRAFFIC,
            KEY_SHOW_NAVI_ALERT, KEY_SHOW_CRUISE_ALERT,
            KEY_SHOW_MUSIC_TITLE, KEY_SHOW_MUSIC_ARTIST, KEY_SHOW_MUSIC_TIME,
            KEY_SHOW_MUSIC_BAR
        )

        // ── 字号/尺寸（px，int）──────────────────
        const val KEY_TS_SPEED = "ts_speed"
        const val KEY_TS_KMH = "ts_kmh"
        const val KEY_TS_LIMIT = "ts_limit"
        const val KEY_TS_TRAFFIC_SEC = "ts_traffic_sec"
        const val KEY_TS_NAVI_DIST = "ts_navi_dist"
        const val KEY_TS_NAVI_ROAD = "ts_navi_road"
        const val KEY_TS_NAVI_DEST = "ts_navi_dest"
        const val KEY_TS_NAVI_ETA = "ts_navi_eta"
        const val KEY_TS_NAVI_ALERT = "ts_navi_alert"
        const val KEY_TS_MUSIC_TITLE = "ts_music_title"
        const val KEY_TS_MUSIC_ARTIST = "ts_music_artist"
        const val KEY_TS_MUSIC_TIME = "ts_music_time"

        /** int 尺寸快照表（key 顺序与默认值一一对应） */
        val INT_KEYS = arrayOf(
            KEY_PAGE_PADDING, KEY_CARD_GAP,
            KEY_SPEED_CARD_W, KEY_MUSIC_CARD_H,
            KEY_TS_SPEED, KEY_TS_KMH, KEY_TS_LIMIT,
            KEY_TS_TRAFFIC_SEC,
            KEY_TS_NAVI_DIST, KEY_TS_NAVI_ROAD, KEY_TS_NAVI_DEST,
            KEY_TS_NAVI_ETA, KEY_TS_NAVI_ALERT,
            KEY_TS_MUSIC_TITLE, KEY_TS_MUSIC_ARTIST, KEY_TS_MUSIC_TIME
        )

        val INT_DEFAULTS = intArrayOf(
            0, 0, 260, 200,
            100, 20, 17, 20, 36, 26, 15, 17, 17,
            24, 15, 15
        )
    }

    private lateinit var mTabs: Array<TextView>
    private lateinit var mPanels: Array<View>
    private lateinit var mTabActiveBg: GradientDrawable
    private val mAllOrder: ArrayList<String> = ArrayList()
    private var mUpdater: UpdateChecker? = null
    private var mTvUpdateStatus: TextView? = null

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

    private val settingItems: List<SettingItem> = listOf(
        // 车速卡（固定）
        SettingItem("speed",   "[车速]车速数字",     KEY_TS_SPEED,       100, KEY_SHOW_SPEED,      false),
        SettingItem("km",      "[车速]km/h 单位",   KEY_TS_KMH,         20,  KEY_SHOW_KMH,        false),
        SettingItem("limit",   "[车速]道路限速",    KEY_TS_LIMIT,       17,  KEY_SHOW_LIMIT,      false),
        SettingItem("traffic", "[车速]红绿灯倒计时", KEY_TS_TRAFFIC_SEC, 20,  KEY_SHOW_TRAFFIC,    false),
        // 导航/巡航（可排序）
        SettingItem("navi_turn",   "[导航]转向图标与距离", KEY_TS_NAVI_DIST,  36, "",                  true),
        SettingItem("navi_road",   "[导航]路名",          KEY_TS_NAVI_ROAD,  26, "",                  true),
        SettingItem("navi_dest",   "[导航]终点名称",      KEY_TS_NAVI_DEST,  15, "",                  true),
        SettingItem("navi_eta",    "[导航]剩余距离时间",  KEY_TS_NAVI_ETA,   17, "",                  true),
        SettingItem("navi_alert",  "[导航]电子眼/服务区", KEY_TS_NAVI_ALERT, 17, KEY_SHOW_NAVI_ALERT, true),
        SettingItem("cruise_road", "[巡航]当前路名",      KEY_TS_NAVI_ROAD,  26, "",                  true),
        SettingItem("cruise_alert","[巡航]电子眼/服务区", KEY_TS_NAVI_ALERT, 17, KEY_SHOW_CRUISE_ALERT, true)
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
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(this, LauncherActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        val saved = prefs().getString(
            "all_row_order",
            "navi_turn,navi_road,navi_dest,navi_eta,navi_alert,cruise_road,cruise_alert"
        )!!
        mAllOrder.clear()
        mAllOrder.addAll(saved.split(","))
        rebuildAllRows(box)
    }

    /** 整屏渲染：固定行 + 顺序行（[onClick] 上下箭头后调用） */
    private fun rebuildAllRows(box: LinearLayout) {
        box.removeAllViews()
        for (item in settingItems) {
            if (!item.orderable) renderSettingRow(box, item, null)
        }
        for (i in mAllOrder.indices) {
            val key = mAllOrder[i]
            val item = settingItems.firstOrNull { it.key == key } ?: continue
            renderSettingRow(box, item, i)
        }
    }

    /**
     * 渲染一行设置：label + CheckBox(显隐) + 字号 picker +
     * 可选上下箭头（[orderablePos] != null 时显示）。
     */
    private fun renderSettingRow(box: LinearLayout, item: SettingItem, orderablePos: Int?) {
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
            view.findViewById<Button>(R.id.btn_up).setOnClickListener {
                if (pos > 0) {
                    java.util.Collections.swap(mAllOrder, pos, pos - 1)
                    saveAllOrder()
                    rebuildAllRows(box)
                }
            }
            view.findViewById<Button>(R.id.btn_down).setOnClickListener {
                if (pos < mAllOrder.size - 1) {
                    java.util.Collections.swap(mAllOrder, pos, pos + 1)
                    saveAllOrder()
                    rebuildAllRows(box)
                }
            }
        } else {
            view.findViewById<View>(R.id.btn_up).visibility = View.GONE
            view.findViewById<View>(R.id.btn_down).visibility = View.GONE
        }
        box.addView(view)
    }

    private fun saveAllOrder() {
        val sb = StringBuilder()
        for (i in mAllOrder.indices) {
            if (i > 0) sb.append(",")
            sb.append(mAllOrder[i])
        }
        val unified = sb.toString()
        val ed = prefs().edit()
        ed.putString("all_row_order", unified)
        val naviFiltered = NaviOrder.filter(unified, cruise = false)
        val cruiseFiltered = NaviOrder.filter(unified, cruise = true)
        if (naviFiltered.isNotEmpty()) ed.putString("navi_row_order", naviFiltered)
        if (cruiseFiltered.isNotEmpty()) ed.putString("cruise_row_order", cruiseFiltered)
        ed.apply()
    }

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
        mTvUpdateStatus = findViewById(R.id.tv_update_status)
        findViewById<TextView>(R.id.tv_version_info).text = buildVersionInfo()
        val btn = findViewById<Button>(R.id.btn_check_update)
        val updater = UpdateChecker(this, object : UpdateChecker.Listener {
            override fun onUpdateStart() {
                mTvUpdateStatus?.text = "正在检查更新…"
            }
            override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
                mTvUpdateStatus?.text = "发现新版本 v${info.versionName}，开始下载…"
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
     *  - versionCode（epoch 秒数 = UTC 秒数）
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
