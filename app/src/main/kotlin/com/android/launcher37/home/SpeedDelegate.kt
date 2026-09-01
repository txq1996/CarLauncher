package com.android.launcher37.home

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher37.AmapNaviListener
import com.android.launcher37.Prefs
import com.android.launcher37.R
import com.android.launcher37.SettingsActivity
import com.android.launcher37.SpeedClient
import com.android.launcher37.SysProps

/**
 * 车速委派：动态渲染车速区域（车速数字、单位、限速、红绿灯倒计时）。
 *
 * 车速区域与导航行序统一在 navi_panel 容器中动态渲染，支持排序。
 * 每个元素独立字号 + 独立显隐，导航/巡航模式分别设置。
 *
 * 数据源优先级：
 * 1. IPC（[SpeedClient]）：实时 GPS 车速
 * 2. AmapNaviListener：导航/巡航时高德广播的 CUR_SPEED
 */
class SpeedDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews
) : AmapNaviListener.Listener, SpeedClient.Listener {
    private val mMiles: Boolean = isMiles(activity)
    private var mSpeedClient: SpeedClient? = null
    @Volatile private var mUseIpcSpeed: Boolean = false

    // 当前模式：false=导航, true=巡航
    private var mCruise: Boolean = false

    // 动态创建的 View 引用
    private var mTvSpeed: TextView? = null
    private var mTvKm: TextView? = null
    private var mTvLimit: TextView? = null
    private var mTvTrafficSec: TextView? = null
    private var mSpeedUnitRow: LinearLayout? = null

    // 当前显示值
    private var mShownSpeed: Int = 0
    private var mLimitKmh: Int = 0
    private var mSpeedText: String = "0"
    private var mLimitText: String = ""
    private var mTrafficText: String = "--"
    private var mTrafficColor: Int = 0

    fun bind() {
        AmapNaviListener.addListener(this)
        mSpeedClient = SpeedClient(activity, this).also { it.start() }
    }

    fun unbind() {
        AmapNaviListener.removeListener(this)
        mSpeedClient?.stop()
        mSpeedClient = null
    }

    /**
     * 由 NaviPanelDelegate 在模式切换时调用：设置当前模式并重新渲染车速区域。
     */
    fun setCruise(cruise: Boolean) {
        if (mCruise != cruise) {
            mCruise = cruise
            renderSpeedRow()
        }
    }

    /**
     * 动态渲染车速区域：根据当前模式 + 行序 + 设置快照，构建 View 并 addView 到 naviPanel 顶部。
     * 只渲染车速相关的 key（speed/speed_unit/limit/traffic），忽略导航行序 key。
     */
    fun renderSpeedRow() {
        val snapshot = SettingsSnapshot.load(activity)
        val orderKey = if (mCruise) SettingsActivity.KEY_CRUISE_ORDER else SettingsActivity.KEY_NAVI_ORDER
        val defaultOrder = if (mCruise) {
            "speed,speed_unit,limit,traffic,cruise_road,cruise_eta_text,cruise_light_count,cruise_direction,cruise_alert"
        } else {
            "speed,speed_unit,limit,traffic,navi_turn,navi_road,navi_dest,navi_eta,navi_eta_text,navi_light_count,navi_exit,navi_direction,navi_alert"
        }
        val order = Prefs.getString(activity, orderKey, defaultOrder)!!
        val keys = order.split(",").filter { it.isNotBlank() }

        // 只处理车速相关的 key
        val speedKeys = keys.filter { it in SPEED_ITEM_KEYS }

        // 字号/显隐 key 前缀
        val tsPrefix = if (mCruise) "ts_cruise_" else "ts_navi_"
        val showPrefix = if (mCruise) "show_cruise_" else "show_navi_"

        // 默认字号
        val defaults = mapOf(
            "speed" to 110,
            "speed_unit" to 20,
            "limit" to 17,
            "traffic" to 36
        )

        // 清除旧的车速 View
        removeSpeedViews()

        // 按行序添加车速 View
        for (key in speedKeys) {
            val showKey = showPrefix + key.replace("speed_unit", "kmh").replace("traffic", "traffic")
            val show = snapshot.show(showKey, true)
            if (!show) continue

            val tsKey = tsPrefix + key.replace("speed_unit", "kmh").replace("traffic", "traffic_sec")
            val def = defaults[key] ?: 17
            val fontPx = snapshot.size(tsKey, def)

            when (key) {
                "speed" -> {
                    val tv = createSpeedTextView(fontPx)
                    mTvSpeed = tv
                    views.naviPanel.addView(tv, 0)
                }
                "speed_unit" -> {
                    val tv = createUnitTextView(fontPx)
                    mTvKm = tv
                    val row = getOrCreateSpeedUnitRow()
                    row.addView(tv, 0)
                }
                "limit" -> {
                    val tv = createLimitTextView(fontPx)
                    mTvLimit = tv
                    val row = getOrCreateSpeedUnitRow()
                    row.addView(tv)
                }
                "traffic" -> {
                    val tv = createTrafficTextView(fontPx)
                    mTvTrafficSec = tv
                    views.naviPanel.addView(tv)
                }
            }
        }

        // 如果有单位/限速行，插入到正确位置
        mSpeedUnitRow?.let { row ->
            if (row.childCount > 0 && row.parent == null) {
                val idx = if (mTvSpeed != null) 1 else 0
                views.naviPanel.addView(row, idx)
            }
        }

        // 恢复当前显示值
        mTvSpeed?.text = mSpeedText
        mTvSpeed?.setTextColor(getSpeedColor())
        mTvLimit?.text = mLimitText
        mTvLimit?.setTextColor(getLimitColor())
        mTvTrafficSec?.text = mTrafficText
        if (mTrafficColor != 0) mTvTrafficSec?.setTextColor(mTrafficColor)
    }

    private fun removeSpeedViews() {
        mTvSpeed?.let { views.naviPanel.removeView(it) }
        mTvSpeed = null
        mTvKm = null
        mTvLimit = null
        mTvTrafficSec?.let { views.naviPanel.removeView(it) }
        mTvTrafficSec = null
        mSpeedUnitRow?.let {
            it.removeAllViews()
            views.naviPanel.removeView(it)
        }
        mSpeedUnitRow = null
    }

    private fun getOrCreateSpeedUnitRow(): LinearLayout {
        if (mSpeedUnitRow != null) return mSpeedUnitRow!!
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mSpeedUnitRow = this
        }
    }

    private fun createSpeedTextView(fontPx: Int): TextView {
        return TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
            setTextColor(getSpeedColor())
            text = mSpeedText
            includeFontPadding = false
            maxLines = 1
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createUnitTextView(fontPx: Int): TextView {
        return TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
            setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
            text = if (mMiles) "mph" else "km/h"
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createLimitTextView(fontPx: Int): TextView {
        return TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
            setTextColor(getLimitColor())
            text = mLimitText
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * activity.resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun createTrafficTextView(fontPx: Int): TextView {
        return TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx.toFloat())
            setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
            text = mTrafficText
            includeFontPadding = false
            maxLines = 1
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * activity.resources.displayMetrics.density).toInt()
            }
        }
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

    // ── SpeedClient 回调（IPC 优先） ──────────────────────────────

    override fun onSpeedChanged(kmh: Int) {
        mUseIpcSpeed = true
        applySpeed(kmh)
    }

    override fun onAccOff() {
        mUseIpcSpeed = false
        val naviSpeed = AmapNaviListener.lastNaviInfo?.curSpeed ?: 0
        val cruiseSpeed = AmapNaviListener.lastCruiseInfo?.curSpeed ?: 0
        applySpeed(if (naviSpeed > 0) naviSpeed else cruiseSpeed)
    }

    // ── AmapNaviListener 回退（IPC 不可用时才处理） ──────────────

    override fun onNaviInfo(info: AmapNaviListener.NaviInfo) {
        if (!mUseIpcSpeed) applySpeed(info.curSpeed)
    }

    override fun onCruiseInfo(info: AmapNaviListener.CruiseInfo) {
        if (!mUseIpcSpeed) applySpeed(info.curSpeed)
    }

    private fun applySpeed(kmh: Int) {
        val target = if (mMiles) (kmh * MILE_RATIO).toInt() else kmh
        if (target == mShownSpeed) return
        mShownSpeed = target
        mSpeedText = target.toString()
        mTvSpeed?.text = mSpeedText
        mTvSpeed?.setTextColor(getSpeedColor())
        mTvLimit?.setTextColor(getLimitColor())
    }

    /** 由 NaviPanelDelegate 在广播到来时回调：更新限速值并刷新超速色 */
    fun setLimit(limitedKmh: Int) {
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
    fun displaySpeed(kmh: Int): Int =
        if (mMiles) Math.round(kmh * MILE_RATIO) else kmh

    companion object {
        private const val KEY_MILES = "persist.sys.isMiles"
        private const val MILE_RATIO = 0.62f

        /** 车速区域相关的 item key 集合 */
        private val SPEED_ITEM_KEYS = setOf("speed", "speed_unit", "limit", "traffic")

        fun isMiles(context: android.content.Context): Boolean =
            "1" == SysProps.get(KEY_MILES, "0")
    }
}
