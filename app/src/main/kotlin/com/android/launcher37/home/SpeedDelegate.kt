package com.android.launcher37.home

import com.android.launcher37.AmapNaviListener
import com.android.launcher37.R
import com.android.launcher37.SysProps

/**
 * 车速委派：从 [AmapNaviListener] 读取高德广播的 CUR_SPEED，渲染限速 / 超速变色 / 红绿灯。
 *
 * 数据源：`AmapNaviListener` 缓存的 NaviInfo.curSpeed（导航/巡航 ICON!=0 期间）与
 * CruiseInfo.curSpeed（巡航 ICON=0）。高德不在前台时不推送 CUR_SPEED，按用户决策
 * tvSpeed 维持上次显示；`AmapNaviListener.start` 后未收到 10001 时缓存为 null，
 * 监听器不会回调，tvSpeed 维持初值 "0"。
 *
 * 持有 Activity 强引用；速度单位（km/h / mph）在构造时一次性读取，运行时不再变。
 */
class SpeedDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews
) : AmapNaviListener.Listener {
    private val mMiles: Boolean = isMiles(activity)
    private var mShownSpeed: Int = 0
    private var mLimitKmh: Int = 0

    fun bind() {
        AmapNaviListener.addListener(this)
    }

    fun unbind() {
        AmapNaviListener.removeListener(this)
    }

    override fun onNaviInfo(info: AmapNaviListener.NaviInfo) {
        applySpeed(info.curSpeed)
    }

    override fun onCruiseInfo(info: AmapNaviListener.CruiseInfo) {
        applySpeed(info.curSpeed)
    }

    private fun applySpeed(kmh: Int) {
        val target = if (mMiles) (kmh * MILE_RATIO).toInt() else kmh
        if (target == mShownSpeed) return
        mShownSpeed = target
        views.tvSpeed.text = target.toString()
        refreshOverspeed()
    }

    /** 由 NaviPanelDelegate 在广播到来时回调：更新限速值并刷新超速色 */
    fun setLimit(limitedKmh: Int) {
        mLimitKmh = limitedKmh
        views.tvLimit.text = if (limitedKmh > 0) "限速${displaySpeed(limitedKmh)}" else ""
        refreshOverspeed()
    }

    private fun refreshOverspeed() {
        val over = mLimitKmh > 0 && mShownSpeed > displaySpeed(mLimitKmh)
        val overColor = activity.resources.getColor(R.color.trafficRed)
        val color = if (over) overColor else activity.resources.getColor(R.color.foreground, activity.theme)
        if (views.tvSpeed.currentTextColor != color) views.tvSpeed.setTextColor(color)
        val limitColor = if (over) overColor else activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        if (views.tvLimit.currentTextColor != limitColor) views.tvLimit.setTextColor(limitColor)
    }

    override fun onTrafficLight(info: AmapNaviListener.TrafficLightInfo) {
        val color = when (info.status) {
            1 -> activity.resources.getColor(R.color.trafficRed, activity.theme)
            4 -> activity.resources.getColor(R.color.trafficGreen, activity.theme)
            else -> activity.resources.getColor(R.color.trafficYellow, activity.theme)
        }
        views.tvTrafficSec.setTextColor(color)
        views.tvTrafficSec.text = if (info.countdown >= 0) "${info.countdown}" else "--"
    }

    override fun onTrafficLightHidden() {
        views.tvTrafficSec.setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
        views.tvTrafficSec.text = "--"
    }

    /** 显示域速度（mph 模式换算） */
    fun displaySpeed(kmh: Int): Int =
        if (mMiles) Math.round(kmh * MILE_RATIO) else kmh

    companion object {
        private const val KEY_MILES = "persist.sys.isMiles"
        private const val MILE_RATIO = 0.62f

        /** 静态工具：LauncherActivity 在 onCreate 时读一次决定 km/h / mph 单位 */
        fun isMiles(context: android.content.Context): Boolean =
            "1" == SysProps.get(KEY_MILES, "0")
    }
}