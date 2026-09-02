package com.android.launcher37.home

import android.view.View
import com.android.launcher37.AmapNaviListener
import com.android.launcher37.FormatUtils
import com.android.launcher37.NaviTextClient
import com.android.launcher37.Prefs
import com.android.launcher37.R
import com.android.launcher37.SettingsActivity

/**
 * 导航面板委派：`NaviTextClient.Listener` + 行序渲染 + 电子眼 / 服务区提醒
 * + AmapNaviListener 全数据扩展条目（ETA/红绿灯数/出口/车头方向）。
 *
 * 设计：
 * - 模式/限速来源：`NaviTextClient`（仍提供 IDLE/NAV/CRUISE 与 limitedSpeed）
 * - 全字段来源：`AmapNaviListener.lastNaviInfo/lastCruiseInfo`（ETA/出口/红绿灯数等
 *   NaviTextClient 未覆盖的字段）
 * - 行序/字号/显隐：`SettingsSnapshot` 驱动，顺序由 `KEY_NAVI_ORDER/KEY_CRUISE_ORDER` 持久化
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

    fun start() {
        mClient.start()
        // 启动时默认显示巡航区域（包含车速）
        speed.setCruise(true)
        views.naviPanel.visibility = View.VISIBLE
        val mockInfo = NaviTextClient.NaviInfo().apply {
            mode = NaviTextClient.Mode.CRUISE
        }
        val cruiseInfo = AmapNaviListener.lastCruiseInfo
        applyNaviOrder(true, mockInfo, cruiseInfo)
    }
    fun stop() = mClient.stop()

    /**
     * 日/夜主题切换后由 onConfigurationChanged 调用：清空结构签名强制全量重建，
     * 让动态渲染的文字/图标按新 uiMode 重新读色（常规 ~1Hz 推送只 setText 不重读色）。
     */
    fun rebuildForThemeChange() {
        mLastStructureSig = null
        val cruise = speed.cruise
        val mockInfo = NaviTextClient.NaviInfo().apply {
            mode = if (cruise) NaviTextClient.Mode.CRUISE else NaviTextClient.Mode.NAV
        }
        applyNaviOrder(cruise, mockInfo, if (cruise) AmapNaviListener.lastCruiseInfo else AmapNaviListener.lastNaviInfo)
    }

    override fun onNaviInfo(info: NaviTextClient.NaviInfo) {
        speed.setLimit(if (info.limitedSpeed > 0) info.limitedSpeed else info.cameraSpeed)
        val cruise = info.mode == NaviTextClient.Mode.CRUISE
        speed.setCruise(cruise)
        views.naviPanel.visibility = View.VISIBLE
        // 合并 AmapNaviListener 全字段（ETA/出口/红绿灯数/车头方向/服务区等）
        val ext = if (cruise) AmapNaviListener.lastCruiseInfo else AmapNaviListener.lastNaviInfo
        applyNaviOrder(cruise, info, ext)
    }

    override fun onNaviStopped() {
        // 导航停止后回到巡航模式（显示巡航信息 + IPC 车速）；无巡航数据时只显示车速区域
        speed.setCruise(true)
        speed.setLimit(-1)
        views.naviPanel.visibility = View.VISIBLE
        val mockInfo = NaviTextClient.NaviInfo().apply {
            mode = NaviTextClient.Mode.CRUISE
        }
        applyNaviOrder(true, mockInfo, AmapNaviListener.lastCruiseInfo)
    }

    /**
     * 渲染入口：结构变化时清除旧子项按 [SettingsActivity.KEY_NAVI_ORDER]/
     * [SettingsActivity.KEY_CRUISE_ORDER] 逐条 addView；结构未变（高德 ~1Hz
     * 的常规推送）时只更新文本与显隐，避免每秒全量重建 View 造成卡顿与 GC 抖动。
     *
     * @param cruise true=巡航模式 false=导航模式
     * @param info NaviTextClient 推过来的基础数据
     * @param ext AmapNaviListener 缓存的扩展数据（巡航用 CruiseInfo，导航用 NaviInfo）
     */
    private fun applyNaviOrder(
        cruise: Boolean,
        info: NaviTextClient.NaviInfo,
        ext: Any?
    ) {
        val orderKey = if (cruise) SettingsActivity.KEY_CRUISE_ORDER else SettingsActivity.KEY_NAVI_ORDER
        val defaultOrder = if (cruise) DEFAULT_CRUISE_ORDER else DEFAULT_NAVI_ORDER
        val order = Prefs.getString(activity, orderKey, defaultOrder)!!
        val keys = order.split(",").filter { it.isNotBlank() }
        val snapshot = snapshotProvider()
        val sig = buildStructureSig(cruise, keys, snapshot)
        if (sig == mLastStructureSig) {
            for (key in keys) {
                if (key !in SPEED_KEYS) renderKey(key, cruise, info, ext, rebuild = false)
            }
            return
        }
        mLastStructureSig = sig
        views.naviPanel.removeAllViews()
        // 先渲染车速区域（动态排序）
        speed.renderSpeedRow(force = true)
        // 再渲染导航行序（跳过已由 SpeedDelegate 渲染的车速 key）
        for (key in keys) {
            if (key !in SPEED_KEYS) renderKey(key, cruise, info, ext, rebuild = true)
        }
    }

    /** 上一次渲染的结构签名（模式 + 行序 + 各行显隐/字号） */
    private var mLastStructureSig: String? = null

    /** 行样式设置来源（显隐 + 字号 SP key 与缺省字号），供渲染与结构签名共用 */
    private class RowStyle(val tsKey: String, val showKey: String, val defaultPx: Int)

    private fun rowStyle(key: String, cruise: Boolean): RowStyle? = when (key) {
        "navi_turn" -> RowStyle(SettingsActivity.KEY_TS_NAVI_TURN, SettingsActivity.KEY_SHOW_NAVI_TURN, 36)
        "navi_road", "cruise_road" -> if (cruise)
            RowStyle(SettingsActivity.KEY_TS_CRUISE_ROAD, SettingsActivity.KEY_SHOW_CRUISE_ROAD, 26)
        else RowStyle(SettingsActivity.KEY_TS_NAVI_ROAD, SettingsActivity.KEY_SHOW_NAVI_ROAD, 26)
        "navi_dest" -> RowStyle(SettingsActivity.KEY_TS_NAVI_DEST, SettingsActivity.KEY_SHOW_NAVI_DEST, 15)
        "navi_eta" -> RowStyle(SettingsActivity.KEY_TS_NAVI_ETA, SettingsActivity.KEY_SHOW_NAVI_ETA, 17)
        "navi_eta_text" -> RowStyle(SettingsActivity.KEY_TS_NAVI_ETA_TEXT, SettingsActivity.KEY_SHOW_NAVI_ETA_TEXT, 17)
        "navi_light_count" -> RowStyle(SettingsActivity.KEY_TS_NAVI_LIGHT_COUNT, SettingsActivity.KEY_SHOW_NAVI_LIGHT_COUNT, 17)
        "navi_exit" -> RowStyle(SettingsActivity.KEY_TS_NAVI_EXIT, SettingsActivity.KEY_SHOW_NAVI_EXIT, 17)
        "navi_direction", "cruise_direction" -> if (cruise)
            RowStyle(SettingsActivity.KEY_TS_CRUISE_DIRECTION, SettingsActivity.KEY_SHOW_CRUISE_DIRECTION, 17)
        else RowStyle(SettingsActivity.KEY_TS_NAVI_DIRECTION, SettingsActivity.KEY_SHOW_NAVI_DIRECTION, 17)
        "navi_alert", "cruise_alert" -> if (cruise)
            RowStyle(SettingsActivity.KEY_TS_CRUISE_ALERT, SettingsActivity.KEY_SHOW_CRUISE_ALERT, 17)
        else RowStyle(SettingsActivity.KEY_TS_NAVI_ALERT, SettingsActivity.KEY_SHOW_NAVI_ALERT, 17)
        else -> null
    }

    /** 结构签名：模式 + 非车速行的显隐/字号取值（车速区结构由 SpeedDelegate 自身签名管理） */
    private fun buildStructureSig(
        cruise: Boolean,
        keys: List<String>,
        snapshot: SettingsSnapshot
    ): String {
        val sb = StringBuilder(if (cruise) "C" else "N")
        for (key in keys) {
            if (key in SPEED_KEYS) continue
            val st = rowStyle(key, cruise) ?: continue
            sb.append('|').append(key)
                .append(snapshot.show(st.showKey, true)).append(',')
                .append(snapshot.size(st.tsKey, st.defaultPx))
        }
        return sb.toString()
    }

    /**
     * 单条渲染分发。每条都先按 SP 字号覆盖（snapshot 提供），再 addView。
     *
     * **模式键选择**：同一字段（如 `cruise_road` vs `navi_road`）在 navi 模式下走
     * `KEY_TS_NAVI_*` / `KEY_SHOW_NAVI_*`，巡航模式走 `KEY_TS_CRUISE_*` /
     * `KEY_SHOW_CRUISE_*` —— 用户可在设置里为两个模式分别调字号和开关。
     */
    private fun renderKey(
        key: String,
        cruise: Boolean,
        info: NaviTextClient.NaviInfo,
        ext: Any?,
        rebuild: Boolean
    ) {
        val snapshot = snapshotProvider()
        when (key) {
            "navi_turn" -> if (!cruise) {
                val iconRes = NaviTextClient.turnIconRes(info.icon)
                if (iconRes != 0) {
                    views.ivTurnIcon.setImageResource(iconRes)
                    // 日/夜双色已烘进资源（drawable-nodpi / drawable-nodpi-night），
                    // 由系统 UiMode 自动切换，无需运行时染色。
                    views.ivTurnIcon.scaleX = if (NaviTextClient.turnIconMirrored(info.icon)) -1f else 1f
                }
                views.tvNaviDist.text = formatDis(info.segRemainDis)
                applyRow(views.naviRowTurn, snapshot,
                    SettingsActivity.KEY_TS_NAVI_TURN,
                    SettingsActivity.KEY_SHOW_NAVI_TURN, defaultPx = 36,
                    applyFontToChildren = true, rebuild = rebuild)
            }
            "navi_road", "cruise_road" -> {
                var road = if (cruise) info.curRoadName else info.nextRoadName
                if (road.isNullOrEmpty()) road = info.curRoadName
                views.tvNaviRoad.text = if (cruise) {
                    road ?: ""
                } else {
                    if (road == null) "" else "进入 $road"
                }
                applyRow(
                    views.tvNaviRoad, snapshot,
                    if (cruise) SettingsActivity.KEY_TS_CRUISE_ROAD else SettingsActivity.KEY_TS_NAVI_ROAD,
                    if (cruise) SettingsActivity.KEY_SHOW_CRUISE_ROAD else SettingsActivity.KEY_SHOW_NAVI_ROAD,
                    defaultPx = 26, rebuild = rebuild
                )
            }
            "navi_dest" -> if (!cruise) {
                views.tvNaviDest.text = info.endPoiName ?: ""
                applyRow(views.tvNaviDest, snapshot,
                    SettingsActivity.KEY_TS_NAVI_DEST,
                    SettingsActivity.KEY_SHOW_NAVI_DEST, defaultPx = 15, rebuild = rebuild)
            }
            "navi_eta" -> if (!cruise) {
                // 巡航误入导航模式时 remainTime/remainDis 为 0，避免仅显示“剩”字
                if (info.remainTime <= 0 && info.remainDis <= 0) {
                    if (!rebuild) views.naviRowEta.visibility = View.GONE
                    return
                }
                views.tvNaviTime.text = "剩${formatDuration(info.remainTime)}"
                views.tvNaviRemain.text = formatRemain(info.remainDis)
                applyRow(views.naviRowEta, snapshot,
                    SettingsActivity.KEY_TS_NAVI_ETA,
                    SettingsActivity.KEY_SHOW_NAVI_ETA, defaultPx = 17,
                    applyFontToChildren = true, rebuild = rebuild)
            }
            "navi_eta_text" -> if (!cruise) {
                val text = (ext as? AmapNaviListener.NaviInfo)?.etaText ?: ""
                val text2 = text?.takeIf { it.isNotBlank() } ?: ""
                applyRow(
                    views.tvNaviEtaText, snapshot,
                    SettingsActivity.KEY_TS_NAVI_ETA_TEXT,
                    SettingsActivity.KEY_SHOW_NAVI_ETA_TEXT,
                    defaultPx = 17,
                    forceText = text2,
                    rebuild = rebuild
                )
            }
            "navi_light_count" -> if (!cruise) {
                val n = (ext as? AmapNaviListener.NaviInfo)?.remainLightNum ?: 0
                val text = if (n > 0) "剩${n}个红绿灯" else ""
                applyRow(
                    views.tvNaviLightCount, snapshot,
                    SettingsActivity.KEY_TS_NAVI_LIGHT_COUNT,
                    SettingsActivity.KEY_SHOW_NAVI_LIGHT_COUNT,
                    defaultPx = 17,
                    forceText = text,
                    rebuild = rebuild
                )
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
                applyRow(
                    views.tvNaviExit, snapshot,
                    SettingsActivity.KEY_TS_NAVI_EXIT,
                    SettingsActivity.KEY_SHOW_NAVI_EXIT, defaultPx = 17,
                    forceText = text,
                    rebuild = rebuild
                )
            }
            "navi_direction", "cruise_direction" -> {
                val deg = when (ext) {
                    is AmapNaviListener.NaviInfo -> ext.carDirection
                    is AmapNaviListener.CruiseInfo -> ext.carDirection
                    else -> -1
                }
                val text = if (deg >= 0) "车头 ${directionText(deg)}" else ""
                applyRow(
                    views.tvNaviDirection, snapshot,
                    if (cruise) SettingsActivity.KEY_TS_CRUISE_DIRECTION else SettingsActivity.KEY_TS_NAVI_DIRECTION,
                    if (cruise) SettingsActivity.KEY_SHOW_CRUISE_DIRECTION else SettingsActivity.KEY_SHOW_NAVI_DIRECTION,
                    defaultPx = 17,
                    forceText = text,
                    rebuild = rebuild
                )
            }
            "navi_alert", "cruise_alert" -> {
                val alertOn = if (cruise) snapshot.show(SettingsActivity.KEY_SHOW_CRUISE_ALERT)
                else snapshot.show(SettingsActivity.KEY_SHOW_NAVI_ALERT)
                renderAlert(info, alertOn, cruise)
                // alert 关闭时不要 addView 空 TextView，否则会占据 navi_panel 布局高度
                if (views.tvNaviAlert.text.isNotEmpty()) {
                    applyRow(
                        views.tvNaviAlert, snapshot,
                        if (cruise) SettingsActivity.KEY_TS_CRUISE_ALERT else SettingsActivity.KEY_TS_NAVI_ALERT,
                        showKey = "",  // 显隐已在 renderAlert 里判定
                        defaultPx = 17,
                        applyFontToChildren = false,
                        rebuild = rebuild
                    )
                } else if (!rebuild) {
                    views.tvNaviAlert.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 通用扩展行写入 + 添加。
     *
     * 显示开关缺失（showKey 为空或不存在）→ 视为开启（向后兼容 alert 的特殊用法）。
     * 字号从 SP 读，默认 [defaultPx]。
     *
     * @param forceText 若非 null，则用此值覆盖 view 已有 text（典型：ETA/红绿灯/出口的动态文本）；
     *        为 null 时仅按 SP 字号/开关更新（典型：road/dest/eta/turn 行已先在调用方写过 text）。
     * @param applyFontToChildren true 时把字号应用到 view 内部所有 TextView（如 navi_row_turn/eta
     *        这种 LinearLayout 容器）；false 时只对当前 view 设字号。
     */
    private fun applyRow(
        tv: android.view.View,
        snapshot: SettingsSnapshot,
        fontKey: String,
        showKey: String,
        defaultPx: Int,
        forceText: String? = null,
        applyFontToChildren: Boolean = false,
        rebuild: Boolean
    ) {
        val showOn = if (showKey.isEmpty()) true else snapshot.show(showKey, true)
        val textOk = when {
            forceText == null -> true
            forceText.isEmpty() -> false
            else -> true
        }
        if (rebuild) {
            if (!showOn || !textOk) {
                // 不 addView，避免空白行占据 navi_panel 布局高度
                return
            }
            val fontPx = snapshot.size(fontKey, defaultPx)
            if (forceText != null && tv is android.widget.TextView) {
                tv.text = forceText
            }
            applyFont(tv, fontPx, applyFontToChildren)
            views.naviPanel.addView(tv)
        } else {
            // 结构未变：只同步文本显隐，不重建、不重设字号
            tv.visibility = if (showOn && textOk) View.VISIBLE else View.GONE
        }
    }

    /**
     * 递归设置字号。对容器类 view 会把所有 TextView 子项一起覆盖。
     */
    private fun applyFont(v: android.view.View, px: Int, recursive: Boolean) {
        if (v is android.widget.TextView) {
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px.toFloat())
            v.setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
        }
        if (recursive && v is android.view.ViewGroup) {
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

    private fun renderAlert(info: NaviTextClient.NaviInfo, enabled: Boolean, cruise: Boolean = false) {
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
            // 字号与颜色由调用方 applyRow 通过 KEY_TS_NAVI/CRUISE_ALERT 决定
        } else {
            views.tvNaviAlert.text = ""
        }
    }

    private fun formatDis(meter: Int): String = FormatUtils.formatDistance(meter)
    private fun formatRemain(meter: Int): String = FormatUtils.formatRemainDistance(meter)
    private fun formatDuration(s: Int): String = FormatUtils.formatDuration(s)

    companion object {
        // 行序 SP key 单一来源在 SettingsActivity（与设置页共用）
        private val SPEED_KEYS = setOf("speed", "speed_unit", "limit", "traffic")

        /** 默认导航行序（车速 + 行序），SpeedDelegate 排序车速区时也引用 */
        internal const val DEFAULT_NAVI_ORDER = "speed,speed_unit,limit,traffic,navi_turn,navi_road,navi_dest,navi_eta,navi_eta_text,navi_light_count,navi_exit,navi_direction,navi_alert"

        /** 默认巡航行序（车速 + 行序） */
        internal const val DEFAULT_CRUISE_ORDER = "speed,speed_unit,limit,traffic,cruise_road,cruise_direction,cruise_alert"
    }
}