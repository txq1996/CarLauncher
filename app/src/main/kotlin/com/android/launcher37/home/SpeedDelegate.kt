package com.android.launcher37.home

import android.view.View
import com.android.launcher37.R
import com.android.launcher37.SettingsActivity
import com.android.launcher37.SpeedClient
import com.android.launcher37.SysProps

/**
 * 车速委派：`SpeedClient.Listener` + 限速 / 超速变色 / 红绿灯渲染。
 *
 * 持有 Activity 强引用 + SpeedClient 强引用；速度单位（km/h / mph）
 * 在构造时一次性读取，运行时不再变。
 */
class SpeedDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews
) : SpeedClient.Listener {
    private val mClient: SpeedClient = SpeedClient(activity, this)
    private val mMiles: Boolean = isMiles(activity)
    private var mShownSpeed: Int = 0
    private var mLimitKmh: Int = 0

    fun start() = mClient.start()
    fun stop() = mClient.stop()

    override fun onSpeedChanged(kmh: Int) {
        val target = if (mMiles) (kmh * MILE_RATIO).toInt() else kmh
        if (target == mShownSpeed) return
        mShownSpeed = target
        views.tvSpeed.text = target.toString()
        refreshOverspeed()
    }

    override fun onAccOff() {
        val target = if (mMiles) (0 * MILE_RATIO).toInt() else 0
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
        val overColor = activity.resources.getColor(R.color.error)
        val color = if (over) overColor else activity.resources.getColor(R.color.onSurface, activity.theme)
        if (views.tvSpeed.currentTextColor != color) views.tvSpeed.setTextColor(color)
        val limitColor = if (over) overColor else activity.resources.getColor(R.color.onSurfaceVariant, activity.theme)
        if (views.tvLimit.currentTextColor != limitColor) views.tvLimit.setTextColor(limitColor)
    }

    /** 红绿灯倒计时（TrafficLightClient.Listener 转发）；status: 1=红 / 4=绿 / 其他=黄 */
    fun onTrafficLight(status: Int, countdown: Int) {
        val color = when (status) {
            1 -> activity.resources.getColor(R.color.trafficRed, activity.theme)
            4 -> activity.resources.getColor(R.color.trafficGreen, activity.theme)
            else -> activity.resources.getColor(R.color.trafficYellow, activity.theme)
        }
        views.tvTrafficSec.setTextColor(color)
        views.tvTrafficSec.text = if (countdown >= 0) "$countdown" else "--"
    }

    fun onTrafficLightHidden() {
        views.tvTrafficSec.setTextColor(activity.resources.getColor(R.color.onSurfaceVariant, activity.theme))
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
