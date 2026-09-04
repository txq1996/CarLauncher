package com.android.launcher37.home.widget
import com.android.launcher37.R

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher37.navi.AmapNaviListener
import com.android.launcher37.util.FormatUtils
import com.android.launcher37.navi.NaviTextClient
import com.android.launcher37.SettingsActivity
import com.android.launcher37.navi.SpeedClient
import com.android.launcher37.util.SysProps

/**
 * 车速/导航 Widget：车速区（数字/单位/限速/红绿灯倒计时）+ 导航行序渲染
 * （原 SpeedDelegate + NaviPanelDelegate 逻辑合并迁移，自包含）。
 *
 * 数据源（三路独立 receiver，合并决策）：
 * - [SpeedClient]（MS IPC 车速）：>1 km/h 优先（实时性好）
 * - [AmapNaviListener]（高德广播）：MS 无效时用 CUR_SPEED；红绿灯倒计时
 * - [NaviTextClient]（高德广播基础字段）：模式/限速/转向/路名/终点/ETA
 *
 * 行序/字号/显隐为实例自身 config（设计器"属性"面板独立调整，config 键沿用原
 * 设置页 SP 键名）；结构签名未变化时高德 ~1Hz 推送只更新文本，不重建 View。
 * 允许多实例：每个实例独立注册三路监听，渲染到各自的 navi_panel。
 */
class SpeedWidget(activity: Activity, spec: WidgetSpec) : WidgetView(activity, spec, R.layout.card_speed),
    SpeedClient.Listener, AmapNaviListener.Listener, NaviTextClient.Listener {

    override val displayName = "车速/导航"

    private val mMiles: Boolean = isMiles(activity)

    // ── view 引用（card_speed.xml 固定行） ──────────────
    private lateinit var mPanel: LinearLayout
    private lateinit var mRowTurn: LinearLayout
    private lateinit var mRowEta: LinearLayout
    private lateinit var mIvTurnIcon: ImageView
    private lateinit var mTvDist: TextView
    private lateinit var mTvRoad: TextView
    private lateinit var mTvDest: TextView
    private lateinit var mTvRemain: TextView
    private lateinit var mTvTime: TextView
    private lateinit var mTvAlert: TextView
    private lateinit var mTvEtaText: TextView
    private lateinit var mTvLightCount: TextView
    private lateinit var mTvExit: TextView
    private lateinit var mTvDirection: TextView

    // ── 车速状态（原 SpeedDelegate） ────────────────────
    private var mSpeedClient: SpeedClient? = null
    private var mIpcSpeed: Int = 0
    private var mAmapSpeed: Int? = null
    private var mCruise: Boolean = false
    private var mSpeedSig: String? = null
    private var mTvSpeed: TextView? = null
    private var mTvKm: TextView? = null
    private var mTvLimit: TextView? = null
    private var mTvTrafficSec: TextView? = null
    private var mSpeedUnitRow: LinearLayout? = null
    private var mShownSpeed: Int = 0
    private var mLimitKmh: Int = 0
    private var mSpeedText: String = "0"
    private var mLimitText: String = ""
    private var mTrafficText: String = "--"
    private var mTrafficColor: Int = 0

    // ── 导航面板状态（原 NaviPanelDelegate） ────────────
    private val mNaviClient: NaviTextClient = NaviTextClient(activity, this)
    private var mLastStructureSig: String? = null

    override fun onBind() {
        setCardBackground(true)
        mPanel = findViewById(R.id.navi_panel)
        mRowTurn = findViewById(R.id.navi_row_turn)
        mRowEta = findViewById(R.id.navi_row_eta)
        mIvTurnIcon = findViewById(R.id.iv_turn_icon)
        mTvDist = findViewById(R.id.tv_navi_dist)
        mTvRoad = findViewById(R.id.tv_navi_road)
        mTvDest = findViewById(R.id.tv_navi_dest)
        mTvRemain = findViewById(R.id.tv_navi_remain)
        mTvTime = findViewById(R.id.tv_navi_time)
        mTvAlert = findViewById(R.id.tv_navi_alert)
        mTvEtaText = findViewById(R.id.tv_navi_eta_text)
        mTvLightCount = findViewById(R.id.tv_navi_light_count)
        mTvExit = findViewById(R.id.tv_navi_exit)
        mTvDirection = findViewById(R.id.tv_navi_direction)
    }

    /** 属性面板：行显隐+字号合并为一行（SHOW_SIZE，导航/巡航共用一套），外加两个行序 */
    override val props: List<WidgetProp>
        get() {
            val list = ArrayList<WidgetProp>()
            for (p in NAVI_PROPS) list.add(WidgetProp(p.showKey, p.label, PropType.SHOW_SIZE,
                p.sizeDefault.toString(), min = 10, max = p.sizeMax, pairKey = p.sizeKey))
            list.add(WidgetProp(SettingsActivity.KEY_NAVI_ORDER, "导航行序", PropType.ORDER, DEFAULT_NAVI_ORDER, choices = NAVI_ORDER_ITEMS))
            list.add(WidgetProp(SettingsActivity.KEY_CRUISE_ORDER, "巡航行序", PropType.ORDER, DEFAULT_CRUISE_ORDER, choices = CRUISE_ORDER_ITEMS))
            return list
        }

    override fun onPropChanged(key: String, value: String) {
        // 结构签名清空强制全量重建（字号/显隐/行序变更后实时生效）
        mLastStructureSig = null
        mSpeedSig = null
        val cruise = mCruise
        val mockInfo = NaviTextClient.NaviInfo().apply {
            mode = if (cruise) NaviTextClient.Mode.CRUISE else NaviTextClient.Mode.NAV
        }
        applyNaviOrder(cruise, mockInfo, if (cruise) AmapNaviListener.lastCruiseInfo else AmapNaviListener.lastNaviInfo)
    }

    override fun start() {
        AmapNaviListener.addListener(this)
        mSpeedClient = SpeedClient(activity, this).also { it.start() }
        mNaviClient.start()
        // 启动时默认显示巡航区域（包含车速）
        setCruise(true)
        mPanel.visibility = View.VISIBLE
        val mockInfo = NaviTextClient.NaviInfo().apply { mode = NaviTextClient.Mode.CRUISE }
        applyNaviOrder(true, mockInfo, AmapNaviListener.lastCruiseInfo)
    }

    override fun stop() {
        AmapNaviListener.removeListener(this)
        mSpeedClient?.stop()
        mSpeedClient = null
        mNaviClient.stop()
    }

    /** 日/夜主题切换后清空结构签名强制全量重建（动态文字/图标重读色） */
    override fun onThemeChange() {
        setCardBackground(true)
        mLastStructureSig = null
        mSpeedSig = null
        val cruise = mCruise
        val mockInfo = NaviTextClient.NaviInfo().apply { mode = if (cruise) NaviTextClient.Mode.CRUISE else NaviTextClient.Mode.NAV }
        applyNaviOrder(cruise, mockInfo, if (cruise) AmapNaviListener.lastCruiseInfo else AmapNaviListener.lastNaviInfo)
        // 静态 XML 行的固定颜色重读主题（动态行由上面的重建重取）
        fun text(id: Int, color: Int) {
            findViewById<TextView>(id)?.setTextColor(
                activity.resources.getColor(color, activity.theme)
            )
        }
        text(R.id.tv_navi_dist, R.color.foreground)
        text(R.id.tv_navi_road, R.color.foreground)
        text(R.id.tv_navi_dest, R.color.foreground_secondary)
        text(R.id.tv_navi_eta_text, R.color.foreground_tertiary)
        text(R.id.tv_navi_light_count, R.color.foreground_tertiary)
        text(R.id.tv_navi_exit, R.color.foreground_tertiary)
        text(R.id.tv_navi_direction, R.color.foreground_tertiary)
        text(R.id.tv_navi_alert, R.color.foreground_tertiary)
        text(R.id.tv_navi_remain, R.color.foreground_tertiary)
        text(R.id.tv_navi_time, R.color.foreground_tertiary)
    }

    override fun destroy() {
        stop()
    }

    // ═══════════════ 车速区渲染（原 SpeedDelegate） ═══════════════

    private fun setCruise(cruise: Boolean) {
        if (mCruise != cruise) {
            mCruise = cruise
            renderSpeedRow()
        }
    }

    /**
     * 动态渲染车速区域：根据当前模式 + 行序 + 实例 config，构建 View 并 addView 到面板顶部。
     * 结构签名（模式 + 显隐 + 字号）未变化时跳过重建；[force] 由面板全量重建路径调用。
     */
    private fun renderSpeedRow(force: Boolean = false) {
        val orderKey = if (mCruise) SettingsActivity.KEY_CRUISE_ORDER else SettingsActivity.KEY_NAVI_ORDER
        val defaultOrder = if (mCruise) DEFAULT_CRUISE_ORDER else DEFAULT_NAVI_ORDER

        // 导航/巡航共用一套字号/显隐 config（show_navi_* / ts_navi_*），仅行序分两套
        val speedKeys = normalizeOrder(cfg(orderKey, defaultOrder).split(",").filter { it.isNotBlank() }, mCruise)
            .filter { it in SPEED_ITEM_KEYS }

        val tsPrefix = "ts_navi_"
        val showPrefix = "show_navi_"
        val defaults = mapOf(
            "speed" to 110, "speed_unit" to 20, "limit" to 17, "traffic" to 36
        )

        val shown = ArrayList<Pair<String, Int>>()
        val sig = StringBuilder(if (mCruise) "C" else "N")
        for (key in speedKeys) {
            val showKey = showPrefix + key.replace("speed_unit", "kmh")
            if (!cfgBool(showKey, true)) continue
            val tsKey = tsPrefix + key.replace("speed_unit", "kmh").replace("traffic", "traffic_sec")
            val fontPx = cfgInt(tsKey, defaults[key] ?: 17)
            sig.append('|').append(key).append(':').append(fontPx)
            shown.add(key to fontPx)
        }
        if (!force && sig.toString() == mSpeedSig) return
        mSpeedSig = sig.toString()

        removeSpeedViews()
        for ((key, fontPx) in shown) {
            when (key) {
                "speed" -> {
                    val tv = createSpeedTextView(fontPx)
                    mTvSpeed = tv
                    mPanel.addView(tv, 0)
                }
                "speed_unit" -> {
                    val tv = createUnitTextView(fontPx)
                    mTvKm = tv
                    getOrCreateSpeedUnitRow().addView(tv, 0)
                }
                "limit" -> {
                    val tv = createLimitTextView(fontPx)
                    mTvLimit = tv
                    getOrCreateSpeedUnitRow().addView(tv)
                }
                "traffic" -> {
                    val tv = createTrafficTextView(fontPx)
                    mTvTrafficSec = tv
                    mPanel.addView(tv)
                }
            }
        }
        mSpeedUnitRow?.let { row ->
            if (row.childCount > 0 && row.parent == null) {
                val idx = if (mTvSpeed != null) 1 else 0
                mPanel.addView(row, idx)
            }
        }
        mTvSpeed?.text = mSpeedText
        mTvSpeed?.setTextColor(getSpeedColor())
        mTvLimit?.text = mLimitText
        mTvLimit?.setTextColor(getLimitColor())
        mTvTrafficSec?.text = mTrafficText
        if (mTrafficColor != 0) mTvTrafficSec?.setTextColor(mTrafficColor)
    }

    private fun removeSpeedViews() {
        mTvSpeed?.let { mPanel.removeView(it) }
        mTvSpeed = null
        mTvKm = null
        mTvLimit = null
        mTvTrafficSec?.let { mPanel.removeView(it) }
        mTvTrafficSec = null
        mSpeedUnitRow?.let {
            it.removeAllViews()
            mPanel.removeView(it)
        }
        mSpeedUnitRow = null
    }

    private fun getOrCreateSpeedUnitRow(): LinearLayout {
        if (mSpeedUnitRow != null) return mSpeedUnitRow!!
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mSpeedUnitRow = this
        }
    }

    private fun createSpeedTextView(fontPx: Int): TextView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
        setTextColor(getSpeedColor())
        text = mSpeedText
        includeFontPadding = false
        maxLines = 1
        paint.isFakeBoldText = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createUnitTextView(fontPx: Int): TextView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
        setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
        text = if (mMiles) "mph" else "km/h"
        includeFontPadding = false
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createLimitTextView(fontPx: Int): TextView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
        setTextColor(getLimitColor())
        text = mLimitText
        includeFontPadding = false
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * activity.resources.displayMetrics.density).toInt() }
    }

    private fun createTrafficTextView(fontPx: Int): TextView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
        setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
        text = mTrafficText
        includeFontPadding = false
        maxLines = 1
        paint.isFakeBoldText = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4 * activity.resources.displayMetrics.density).toInt() }
    }

    private fun getSpeedColor(): Int {
        val over = mLimitKmh > 0 && mShownSpeed > displaySpeed(mLimitKmh)
        return if (over) activity.resources.getColor(R.color.trafficRed)
        else activity.resources.getColor(R.color.foreground, activity.theme)
    }

    private fun getLimitColor(): Int {
        val over = mLimitKmh > 0 && mShownSpeed > displaySpeed(mLimitKmh)
        return if (over) activity.resources.getColor(R.color.trafficRed)
        else activity.resources.getColor(R.color.foreground_secondary, activity.theme)
    }

    // ── SpeedClient 回调（MS 优先源，>1 视为有效） ────────

    override fun onSpeedChanged(kmh: Int) {
        mIpcSpeed = kmh
        applySpeed(resolveSpeed())
    }

    override fun onAccOff() {
        // ACC 关：归零复位，两路缓存一并失效
        mAmapSpeed = null
        mIpcSpeed = 0
        applySpeed(0)
    }

    // ── AmapNaviListener 回调 ────────────────────────────

    override fun onNaviInfo(info: AmapNaviListener.NaviInfo) {
        mAmapSpeed = info.curSpeed
        applySpeed(resolveSpeed())
    }

    override fun onCruiseInfo(info: AmapNaviListener.CruiseInfo) {
        mAmapSpeed = info.curSpeed
        applySpeed(resolveSpeed())
    }

    override fun onNavigationEnded() {
        mAmapSpeed = null
        applySpeed(resolveSpeed())
    }

    override fun onCruiseEnded() {
        mAmapSpeed = null
        applySpeed(resolveSpeed())
    }

    /** 车速仲裁：MS GPS 车速 >1 km/h 优先；MS 无效或无数据时用高德，再回落 MS 值 */
    private fun resolveSpeed(): Int =
        if (mIpcSpeed > IPC_MIN_VALID) mIpcSpeed else (mAmapSpeed ?: mIpcSpeed)

    private fun applySpeed(kmh: Int) {
        val target = if (mMiles) (kmh * MILE_RATIO).toInt() else kmh
        if (target == mShownSpeed) return
        mShownSpeed = target
        mSpeedText = target.toString()
        mTvSpeed?.text = mSpeedText
        mTvSpeed?.setTextColor(getSpeedColor())
        mTvLimit?.setTextColor(getLimitColor())
    }

    /** 更新限速值并刷新超速色（NaviTextClient 推送路径） */
    private fun setLimit(limitedKmh: Int) {
        mLimitKmh = limitedKmh
        mLimitText = if (limitedKmh > 0) "限速${displaySpeed(limitedKmh)}" else ""
        mTvLimit?.text = mLimitText
        mTvLimit?.setTextColor(getLimitColor())
        mTvSpeed?.setTextColor(getSpeedColor())
    }

    override fun onTrafficLight(info: AmapNaviListener.TrafficLightInfo) {
        val color = when (info.status) {
            1 -> activity.resources.getColor(R.color.trafficRed, activity.theme)
            4 -> activity.resources.getColor(R.color.trafficGreen, activity.theme)
            else -> activity.resources.getColor(R.color.trafficYellow, activity.theme)
        }
        mTrafficText = if (info.countdown >= 0) "${info.countdown}" else "--"
        mTrafficColor = color
        mTvTrafficSec?.setTextColor(color)
        mTvTrafficSec?.text = mTrafficText
    }

    override fun onTrafficLightHidden() {
        mTrafficText = "--"
        mTrafficColor = activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        mTvTrafficSec?.setTextColor(mTrafficColor)
        mTvTrafficSec?.text = mTrafficText
    }

    /** 显示域速度（mph 模式换算） */
    fun displaySpeed(kmh: Int): Int = if (mMiles) Math.round(kmh * MILE_RATIO) else kmh

    // ═══════════════ 导航行渲染（原 NaviPanelDelegate） ═══════════════

    override fun onNaviInfo(info: NaviTextClient.NaviInfo) {
        setLimit(if (info.limitedSpeed > 0) info.limitedSpeed else info.cameraSpeed)
        val cruise = info.mode == NaviTextClient.Mode.CRUISE
        setCruise(cruise)
        mPanel.visibility = View.VISIBLE
        // 合并 AmapNaviListener 全字段（ETA/出口/红绿灯数/车头方向/服务区等）
        val ext = if (cruise) AmapNaviListener.lastCruiseInfo else AmapNaviListener.lastNaviInfo
        applyNaviOrder(cruise, info, ext)
    }

    override fun onNaviStopped() {
        // 导航停止后回到巡航模式（显示巡航信息 + IPC 车速）；无巡航数据时只显示车速区域
        setCruise(true)
        setLimit(-1)
        mPanel.visibility = View.VISIBLE
        val mockInfo = NaviTextClient.NaviInfo().apply { mode = NaviTextClient.Mode.CRUISE }
        applyNaviOrder(true, mockInfo, AmapNaviListener.lastCruiseInfo)
    }

    /** 行序归一：过滤未知键（历史损坏数据），缺失候选项按默认顺序补到尾部 */
    private fun normalizeOrder(keys: List<String>, cruise: Boolean): List<String> {
        val items = if (cruise) CRUISE_ORDER_ITEMS else NAVI_ORDER_ITEMS
        val valid = items.map { it.second }.toSet()
        val out = keys.filter { it in valid }.toMutableList()
        for ((_, k) in items) if (k !in out) out.add(k)
        return out
    }

    /**
     * 渲染入口：结构变化时清除旧子项按行序逐条 addView；结构未变（高德 ~1Hz
     * 的常规推送）时只更新文本与显隐，避免每秒全量重建 View。
     */
    private fun applyNaviOrder(cruise: Boolean, info: NaviTextClient.NaviInfo, ext: Any?) {
        val orderKey = if (cruise) SettingsActivity.KEY_CRUISE_ORDER else SettingsActivity.KEY_NAVI_ORDER
        val defaultOrder = if (cruise) DEFAULT_CRUISE_ORDER else DEFAULT_NAVI_ORDER
        val keys = normalizeOrder(cfg(orderKey, defaultOrder).split(",").filter { it.isNotBlank() }, cruise)
        val sig = buildStructureSig(cruise, keys)
        if (sig == mLastStructureSig) {
            for (key in keys) {
                if (key !in SPEED_KEYS) renderKey(key, cruise, info, ext, rebuild = false)
            }
            return
        }
        mLastStructureSig = sig
        mPanel.removeAllViews()
        // 先渲染车速区域（动态排序）
        renderSpeedRow(force = true)
        // 再渲染导航行序（跳过已由车速区渲染的 key）
        for (key in keys) {
            if (key !in SPEED_KEYS) renderKey(key, cruise, info, ext, rebuild = true)
        }
    }

    /** 行样式设置来源（显隐 + 字号 config 键与缺省字号），供渲染与结构签名共用。
     *  导航/巡航共用一套 navi config 键，仅行序键名区分行。 */
    private class RowStyle(val tsKey: String, val showKey: String, val defaultPx: Int)

    private fun rowStyle(key: String, cruise: Boolean): RowStyle? = when (key) {
        "navi_turn" -> RowStyle(SettingsActivity.KEY_TS_NAVI_TURN, SettingsActivity.KEY_SHOW_NAVI_TURN, 36)
        "navi_road", "cruise_road" -> RowStyle(SettingsActivity.KEY_TS_NAVI_ROAD, SettingsActivity.KEY_SHOW_NAVI_ROAD, 26)
        "navi_dest" -> RowStyle(SettingsActivity.KEY_TS_NAVI_DEST, SettingsActivity.KEY_SHOW_NAVI_DEST, 15)
        "navi_eta" -> RowStyle(SettingsActivity.KEY_TS_NAVI_ETA, SettingsActivity.KEY_SHOW_NAVI_ETA, 17)
        "navi_eta_text" -> RowStyle(SettingsActivity.KEY_TS_NAVI_ETA_TEXT, SettingsActivity.KEY_SHOW_NAVI_ETA_TEXT, 17)
        "navi_light_count" -> RowStyle(SettingsActivity.KEY_TS_NAVI_LIGHT_COUNT, SettingsActivity.KEY_SHOW_NAVI_LIGHT_COUNT, 17)
        "navi_exit" -> RowStyle(SettingsActivity.KEY_TS_NAVI_EXIT, SettingsActivity.KEY_SHOW_NAVI_EXIT, 17)
        "navi_direction", "cruise_direction" -> RowStyle(SettingsActivity.KEY_TS_NAVI_DIRECTION, SettingsActivity.KEY_SHOW_NAVI_DIRECTION, 17)
        "navi_alert", "cruise_alert" -> RowStyle(SettingsActivity.KEY_TS_NAVI_ALERT, SettingsActivity.KEY_SHOW_NAVI_ALERT, 17)
        else -> null
    }

    /** 结构签名：模式 + 非车速行的显隐/字号取值（车速区结构由 renderSpeedRow 自身签名管理） */
    private fun buildStructureSig(cruise: Boolean, keys: List<String>): String {
        val sb = StringBuilder(if (cruise) "C" else "N")
        for (key in keys) {
            if (key in SPEED_KEYS) continue
            val st = rowStyle(key, cruise) ?: continue
            sb.append('|').append(key)
                .append(cfgBool(st.showKey, true)).append(',')
                .append(cfgInt(st.tsKey, st.defaultPx))
        }
        return sb.toString()
    }

    /**
     * 单条渲染分发。每条都先按 config 字号覆盖，再 addView。
     * 导航/巡航共用 show_navi_* / ts_navi_* 配置键。
     */
    private fun renderKey(key: String, cruise: Boolean, info: NaviTextClient.NaviInfo, ext: Any?, rebuild: Boolean) {
        when (key) {
            "navi_turn" -> if (!cruise) {
                val iconRes = NaviTextClient.turnIconRes(info.icon)
                if (iconRes != 0) {
                    mIvTurnIcon.setImageResource(iconRes)
                    mIvTurnIcon.scaleX = if (NaviTextClient.turnIconMirrored(info.icon)) -1f else 1f
                }
                mTvDist.text = formatDis(info.segRemainDis)
                applyRow(mRowTurn,
                    SettingsActivity.KEY_TS_NAVI_TURN,
                    SettingsActivity.KEY_SHOW_NAVI_TURN, defaultPx = 36,
                    applyFontToChildren = true, rebuild = rebuild)
            }
            "navi_road", "cruise_road" -> {
                var road = if (cruise) info.curRoadName else info.nextRoadName
                if (road.isNullOrEmpty()) road = info.curRoadName
                mTvRoad.text = if (cruise) {
                    road ?: ""
                } else {
                    if (road == null) "" else "进入 $road"
                }
                applyRow(mTvRoad,
                    SettingsActivity.KEY_TS_NAVI_ROAD,
                    SettingsActivity.KEY_SHOW_NAVI_ROAD,
                    defaultPx = 26, rebuild = rebuild)
            }
            "navi_dest" -> if (!cruise) {
                mTvDest.text = info.endPoiName ?: ""
                applyRow(mTvDest,
                    SettingsActivity.KEY_TS_NAVI_DEST,
                    SettingsActivity.KEY_SHOW_NAVI_DEST, defaultPx = 15, rebuild = rebuild)
            }
            "navi_eta" -> if (!cruise) {
                // 巡航误入导航模式时 remainTime/remainDis 为 0，避免仅显示"剩"字
                if (info.remainTime <= 0 && info.remainDis <= 0) {
                    if (!rebuild) mRowEta.visibility = View.GONE
                    return
                }
                mTvTime.text = "剩${formatDuration(info.remainTime)}"
                mTvRemain.text = formatRemain(info.remainDis)
                applyRow(mRowEta,
                    SettingsActivity.KEY_TS_NAVI_ETA,
                    SettingsActivity.KEY_SHOW_NAVI_ETA, defaultPx = 17,
                    applyFontToChildren = true, rebuild = rebuild)
            }
            "navi_eta_text" -> if (!cruise) {
                val text = (ext as? AmapNaviListener.NaviInfo)?.etaText ?: ""
                val text2 = text?.takeIf { it.isNotBlank() } ?: ""
                applyRow(mTvEtaText,
                    SettingsActivity.KEY_TS_NAVI_ETA_TEXT,
                    SettingsActivity.KEY_SHOW_NAVI_ETA_TEXT,
                    defaultPx = 17, forceText = text2, rebuild = rebuild)
            }
            "navi_light_count" -> if (!cruise) {
                val n = (ext as? AmapNaviListener.NaviInfo)?.remainLightNum ?: 0
                val text = if (n > 0) "剩${n}个红绿灯" else ""
                applyRow(mTvLightCount,
                    SettingsActivity.KEY_TS_NAVI_LIGHT_COUNT,
                    SettingsActivity.KEY_SHOW_NAVI_LIGHT_COUNT,
                    defaultPx = 17, forceText = text, rebuild = rebuild)
            }
            "navi_exit" -> if (!cruise) {
                val extInfo = ext as? AmapNaviListener.NaviInfo
                val name = extInfo?.exitName?.takeIf { it.isNotBlank() } ?: ""
                val dir = extInfo?.exitDirection?.takeIf { it.isNotBlank() } ?: ""
                val text = when {
                    name.isEmpty() -> ""
                    dir.isEmpty() -> name
                    else -> "$name $dir"
                }
                applyRow(mTvExit,
                    SettingsActivity.KEY_TS_NAVI_EXIT,
                    SettingsActivity.KEY_SHOW_NAVI_EXIT, defaultPx = 17,
                    forceText = text, rebuild = rebuild)
            }
            "navi_direction", "cruise_direction" -> {
                val deg = when (ext) {
                    is AmapNaviListener.NaviInfo -> ext.carDirection
                    is AmapNaviListener.CruiseInfo -> ext.carDirection
                    else -> -1
                }
                val text = if (deg >= 0) "车头 ${directionText(deg)}" else ""
                applyRow(mTvDirection,
                    SettingsActivity.KEY_TS_NAVI_DIRECTION,
                    SettingsActivity.KEY_SHOW_NAVI_DIRECTION,
                    defaultPx = 17, forceText = text, rebuild = rebuild)
            }
            "navi_alert", "cruise_alert" -> {
                val alertOn = cfgBool(SettingsActivity.KEY_SHOW_NAVI_ALERT, true)
                renderAlert(info, alertOn)
                // alert 关闭时不要 addView 空 TextView，否则会占据面板布局高度
                if (mTvAlert.text.isNotEmpty()) {
                    applyRow(mTvAlert,
                        SettingsActivity.KEY_TS_NAVI_ALERT,
                        showKey = "",  // 显隐已在 renderAlert 里判定
                        defaultPx = 17,
                        applyFontToChildren = false,
                        rebuild = rebuild)
                } else if (!rebuild) {
                    mTvAlert.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 通用扩展行写入 + 添加。
     * 显示开关缺失（showKey 为空）→ 视为开启（向后兼容 alert 的特殊用法）。
     * @param forceText 若非 null，则用此值覆盖 view 已有 text
     * @param applyFontToChildren true 时把字号应用到 view 内部所有 TextView
     */
    private fun applyRow(
        tv: View,
        fontKey: String,
        showKey: String,
        defaultPx: Int,
        forceText: String? = null,
        applyFontToChildren: Boolean = false,
        rebuild: Boolean
    ) {
        val showOn = if (showKey.isEmpty()) true else cfgBool(showKey, true)
        val textOk = when {
            forceText == null -> true
            forceText.isEmpty() -> false
            else -> true
        }
        if (rebuild) {
            if (!showOn || !textOk) {
                // 不 addView，避免空白行占据面板布局高度
                return
            }
            val fontPx = cfgInt(fontKey, defaultPx)
            if (forceText != null && tv is TextView) {
                tv.text = forceText
            }
            applyFont(tv, fontPx, applyFontToChildren)
            mPanel.addView(tv)
        } else {
            // 结构未变：只同步文本显隐，不重建、不重设字号
            tv.visibility = if (showOn && textOk) View.VISIBLE else View.GONE
        }
    }

    /** 递归设置字号。对容器类 view 会把所有 TextView 子项一起覆盖。 */
    private fun applyFont(v: View, px: Int, recursive: Boolean) {
        if (v is TextView) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px.toFloat())
            v.setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
        }
        if (recursive && v is ViewGroup) {
            for (i in 0 until v.childCount) applyFont(v.getChildAt(i), px, true)
        }
    }

    /** 把 0~360 度的角度转成 8 方位文字（与 AmapNaviListener/原 receiver 行为一致） */
    private fun directionText(degrees: Int): String {
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        var index = Math.round(degrees / 45.0).toInt() % 8
        if (index < 0) index += 8
        return dirs[index]
    }

    private fun renderAlert(info: NaviTextClient.NaviInfo, enabled: Boolean) {
        val alert = StringBuilder()
        if (info.cameraDist >= 0 && info.cameraType >= 0) {
            alert.append(NaviTextClient.cameraTypeName(info.cameraType))
                .append(' ').append(formatDis(info.cameraDist))
            if (info.cameraSpeed > 0) {
                alert.append("·限速").append(displaySpeed(info.cameraSpeed))
            }
        }
        if (info.sapaDist > 0) {
            if (alert.isNotEmpty()) alert.append("  ·  ")
            alert.append(info.sapaName ?: "服务区")
                .append(' ').append(formatDis(info.sapaDist))
        }
        if (enabled && alert.isNotEmpty()) {
            mTvAlert.text = alert.toString()
        } else {
            mTvAlert.text = ""
        }
    }

    private fun formatDis(meter: Int): String = FormatUtils.formatDistance(meter)
    private fun formatRemain(meter: Int): String = FormatUtils.formatRemainDistance(meter)
    private fun formatDuration(s: Int): String = FormatUtils.formatDuration(s)

    companion object {
        private const val KEY_MILES = "persist.sys.isMiles"
        private const val MILE_RATIO = 0.62f

        /** MS IPC 车速有效性阈值：>此值视为定位有效（SpeedClient 已把 1km/h 噪声归 0） */
        private const val IPC_MIN_VALID = 1

        /** 车速区域相关的 item key 集合 */
        private val SPEED_KEYS = setOf("speed", "speed_unit", "limit", "traffic")
        private val SPEED_ITEM_KEYS = SPEED_KEYS

        /** 默认导航/巡航行序（与设置页共用） */
        internal val DEFAULT_NAVI_ORDER = "speed,speed_unit,limit,traffic,navi_turn,navi_road,navi_dest,navi_eta,navi_eta_text,navi_light_count,navi_exit,navi_direction,navi_alert"
        internal val DEFAULT_CRUISE_ORDER = "speed,speed_unit,limit,traffic,cruise_road,cruise_direction,cruise_alert"

        /** 导航/巡航行序有序候选项（显示名 to key），供设计器拖动排序面板使用。
         *  约定 first=显示名、second=存储键（与 WidgetProp.CHOICE 一致），顺序不可颠倒。 */
        private val NAVI_ORDER_ITEMS = listOf(
            "车速" to "speed", "单位" to "speed_unit", "限速" to "limit", "倒计时" to "traffic",
            "转向" to "navi_turn", "路名" to "navi_road", "终点" to "navi_dest",
            "剩余距离时间" to "navi_eta", "预计到达" to "navi_eta_text",
            "红绿灯数" to "navi_light_count", "出口" to "navi_exit",
            "车头方向" to "navi_direction", "电子眼" to "navi_alert"
        )
        private val CRUISE_ORDER_ITEMS = listOf(
            "车速" to "speed", "单位" to "speed_unit", "限速" to "limit", "倒计时" to "traffic",
            "路名" to "cruise_road", "车头方向" to "cruise_direction", "电子眼" to "cruise_alert"
        )

        /** 属性面板：导航/巡航共用的一组行配置（showKey, sizeKey, 标签, 缺省字号, 字号上限） */
        private class PDef(val showKey: String, val sizeKey: String, val label: String, val sizeDefault: Int, val sizeMax: Int)

        private val NAVI_PROPS = listOf(
            PDef("show_navi_speed", "ts_navi_speed", "车速数字", 110, 150),
            PDef("show_navi_kmh", "ts_navi_kmh", "车速单位", 20, 50),
            PDef("show_navi_limit", "ts_navi_limit", "道路限速", 17, 50),
            PDef("show_navi_traffic", "ts_navi_traffic_sec", "红绿灯倒计时", 36, 50),
            PDef("show_navi_turn", "ts_navi_turn", "转向图标与距离", 36, 50),
            PDef("show_navi_road", "ts_navi_road", "路名", 26, 50),
            PDef("show_navi_dest", "ts_navi_dest", "终点名称", 15, 50),
            PDef("show_navi_eta", "ts_navi_eta", "剩余距离与时间", 17, 50),
            PDef("show_navi_eta_text", "ts_navi_eta_text", "预计到达时间", 17, 50),
            PDef("show_navi_light_count", "ts_navi_light_count", "剩余红绿灯数", 17, 50),
            PDef("show_navi_exit", "ts_navi_exit", "出口信息", 17, 50),
            PDef("show_navi_direction", "ts_navi_direction", "车头方向", 17, 50),
            PDef("show_navi_alert", "ts_navi_alert", "电子眼/服务区", 17, 50)
        )

        fun isMiles(context: android.content.Context): Boolean =
            "1" == SysProps.get(KEY_MILES, "0")
    }
}
