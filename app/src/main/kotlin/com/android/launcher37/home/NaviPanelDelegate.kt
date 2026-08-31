package com.android.launcher37.home

import android.view.View
import com.android.launcher37.FormatUtils
import com.android.launcher37.NaviTextClient
import com.android.launcher37.Prefs
import com.android.launcher37.R
import com.android.launcher37.SettingsActivity

/**
 * 导航面板委派：`NaviTextClient.Listener` + 行序渲染 + 电子眼 / 服务区提醒。
 *
 * 持有 Activity 强引用 + NaviTextClient 强引用；
 * 渲染期间通过 [speed] 写限速到车速委派。
 */
class NaviPanelDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews,
    private val snapshotProvider: () -> SettingsSnapshot,
    private val speed: SpeedDelegate
) : NaviTextClient.Listener {
    private val mClient: NaviTextClient = NaviTextClient(activity, this)

    fun start() = mClient.start()
    fun stop() = mClient.stop()

    override fun onNaviInfo(info: NaviTextClient.NaviInfo) {
        speed.setLimit(if (info.limitedSpeed > 0) info.limitedSpeed else info.cameraSpeed)
        val cruise = info.mode == NaviTextClient.Mode.CRUISE
        views.naviPanel.visibility = View.VISIBLE
        applyNaviOrder(cruise, info)
    }

    override fun onNaviStopped() {
        views.naviPanel.visibility = View.GONE
        speed.setLimit(-1)
    }

    private fun applyNaviOrder(cruise: Boolean, info: NaviTextClient.NaviInfo) {
        val orderKey = if (cruise) KEY_CRUISE_ORDER else KEY_NAVI_ORDER
        val defaultOrder = if (cruise) DEFAULT_CRUISE_ORDER else DEFAULT_NAVI_ORDER
        val unified = Prefs.getString(activity, KEY_ALL_ROW_ORDER, null)
        val order: String = when {
            !unified.isNullOrEmpty() -> {
                val filtered = com.android.launcher37.NaviOrder.filter(unified, cruise)
                if (filtered.isNotEmpty()) filtered else Prefs.getString(activity, orderKey, defaultOrder)!!
            }
            else -> Prefs.getString(activity, orderKey, defaultOrder)!!
        }
        val keys = order.split(",")
        views.naviPanel.removeAllViews()
        for (key in keys) renderKey(key, cruise, info)
    }

    private fun renderKey(key: String, cruise: Boolean, info: NaviTextClient.NaviInfo) {
        when (key) {
            "turn" -> if (!cruise) {
                val iconRes = NaviTextClient.turnIconRes(info.icon)
                if (iconRes != 0) {
                    views.ivTurnIcon.setImageResource(iconRes)
                    views.ivTurnIcon.scaleX = if (NaviTextClient.turnIconMirrored(info.icon)) -1f else 1f
                }
                views.tvNaviDist.text = formatDis(info.segRemainDis)
                views.naviPanel.addView(views.naviRowTurn)
            }
            "road" -> {
                var road = if (cruise) info.curRoadName else info.nextRoadName
                if (road.isNullOrEmpty()) road = info.curRoadName
                views.tvNaviRoad.text = if (cruise) {
                    road ?: ""
                } else {
                    if (road == null) "" else "进入 $road"
                }
                views.naviPanel.addView(views.tvNaviRoad)
            }
            "dest" -> if (!cruise) {
                views.tvNaviDest.text = info.endPoiName ?: ""
                views.naviPanel.addView(views.tvNaviDest)
            }
            "eta" -> if (!cruise) {
                views.tvNaviTime.text = "剩${formatDuration(info.remainTime)}"
                views.tvNaviRemain.text = formatRemain(info.remainDis)
                views.naviPanel.addView(views.naviRowEta)
            }
            "alert" -> {
                val snapshot = snapshotProvider()
                val alertOn = if (cruise) snapshot.show(SettingsActivity.KEY_SHOW_CRUISE_ALERT)
                else snapshot.show(SettingsActivity.KEY_SHOW_NAVI_ALERT)
                renderAlert(info, alertOn)
                // alert 关闭时不要 addView 空 TextView，否则会占据 navi_panel 布局高度
                if (views.tvNaviAlert.text.isNotEmpty()) {
                    views.naviPanel.addView(views.tvNaviAlert)
                }
            }
        }
    }

    private fun renderAlert(info: NaviTextClient.NaviInfo, enabled: Boolean) {
        val alert = StringBuilder()
        if (info.cameraDist >= 0 && info.cameraType >= 0) {
            alert.append(NaviTextClient.cameraTypeName(info.cameraType))
                .append(' ').append(formatDis(info.cameraDist))
            if (info.cameraSpeed > 0) {
                alert.append("·限速").append(speed.displaySpeed(info.cameraSpeed))
            }
        }
        if (info.sapaDist > 0) {
            if (alert.isNotEmpty()) alert.append("  ·  ")
            alert.append(info.sapaName ?: "服务区")
                .append(' ').append(formatDis(info.sapaDist))
        }
        if (enabled && alert.isNotEmpty()) {
            views.tvNaviAlert.text = alert.toString()
            views.tvNaviAlert.setTextColor(activity.resources.getColor(R.color.onSurfaceVariant, activity.theme))
        } else {
            views.tvNaviAlert.text = ""
        }
    }

    private fun formatDis(meter: Int): String = FormatUtils.formatDistance(meter)
    private fun formatRemain(meter: Int): String = FormatUtils.formatRemainDistance(meter)
    private fun formatDuration(s: Int): String = FormatUtils.formatDuration(s)

    companion object {
        private const val KEY_CRUISE_ORDER = "cruise_row_order"
        private const val KEY_NAVI_ORDER = "navi_row_order"
        private const val KEY_ALL_ROW_ORDER = "all_row_order"
        private const val DEFAULT_CRUISE_ORDER = "road,alert"
        private const val DEFAULT_NAVI_ORDER = "turn,road,dest,eta,alert"
    }
}
