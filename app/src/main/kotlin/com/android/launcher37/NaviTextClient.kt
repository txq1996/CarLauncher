package com.android.launcher37

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.SystemClock

/**
 * 高德导航文字信息客户端（移植自 launcher36 MyAutoMapReceiver）。
 *
 * 监听 `AUTONAVI_STANDARD_BROADCAST_SEND`，全量解析四类消息：
 * - `KEY_TYPE=10001(0x2711)` 路径信息：转向/路名/距离/限速/电子眼/服务区/终点/限速等
 * - `KEY_TYPE=12110(0x2F4E)` 测速/区间测速路段：**限速的实际有效来源**
 *   （10001 中 `LIMITED_SPEED` 实测恒 -1）
 * - `KEY_TYPE=10019(0x2723)` 导航状态 `EXTRA_STATE`：8=开始导航 24=进入巡航
 *   0/9/12/25=结束或退出
 * - `KEY_TYPE=60021(0xEA85)` 巡航信息
 *
 * 关键行为：
 * - 广播看门狗：高德异常退出（进程被杀/崩溃）收不到 10019 退出广播，
 *   超 [STALE_MS] 未收到任何广播即按退出处理
 * - 模式机 `Mode.IDLE/NAV/CRUISE`：导航中面板显示完整五行；巡航中仅显示路名+提醒
 * - 限速取 `LIMITED_SPEED`，缺席(-1)时回落 `CAMERA_SPEED`
 *   （10001 中 LIMITED_SPEED 恒 -1，有效值来自 12110）
 */
class NaviTextClient(
    private val mContext: Context,
    private val mListener: Listener
) {
    interface Listener {
        fun onNaviInfo(info: NaviInfo)
        fun onNaviStopped()
    }

    enum class Mode { IDLE, NAV, CRUISE }

    /**
     * 导航文字信息聚合。所有字段按广播原值保留，渲染时按模式过滤。
     */
    data class NaviInfo(
        var mode: Mode = Mode.IDLE,
        // ── 10001 引导信息 ──
        var icon: Int = 0,
        var curRoadName: String? = null,
        var nextRoadName: String? = null,
        var segRemainDis: Int = 0,
        var remainDis: Int = 0,
        var remainTime: Int = 0,
        var limitedSpeed: Int = -1,
        var endPoiName: String? = null,
        // ── 电子眼（导航/巡航共用） ──
        var cameraDist: Int = -1,
        var cameraType: Int = -1,
        var cameraSpeed: Int = -1,
        // ── 服务区（高速场景） ──
        var sapaDist: Int = -1,
        var sapaName: String? = null
    )

    companion object {
        private const val KEY_TYPE_ROUTE = 0x2711     // 10001
        private const val KEY_TYPE_STATE = 0x2723     // 10019
        private const val KEY_TYPE_CRUISE = 0xEA85    // 60021 巡航
        private const val KEY_TYPE_INTERVAL = 0x2F4E  // 12110 区间测速/测速路段

        /** 广播中断超时：高德正常推送周期约 0.6~1s，导航/巡航中超此值视为已异常退出 */
        private const val STALE_MS = 10_000L

        // EXTRA_STATE 取值（新旧版本高德并存）
        private const val STATE_EXIT_LEGACY = 0
        private const val STATE_NAV_START = 8
        private const val STATE_NAV_EXIT = 9
        private const val STATE_NAV_EXIT_ALT = 12
        private const val STATE_CRUISE_ENTER = 24
        private const val STATE_CRUISE_EXIT = 25

/**
         * 高位IC 转向类型 → 本应用转向图标资源 id。
         * 对应 `drawable-nodpi/navinfo_icon{2..20}_white.png` 共 19 个白色位图。
         * 后续白天/夜晚不同色版本用 `navinfo_icon{N}_day.png` / `_night.png` 替换此处即可。
         */
        @JvmStatic
        fun turnIconRes(icon: Int): Int = when (icon) {
            2 -> R.drawable.navinfo_icon2_white
            3 -> R.drawable.navinfo_icon3_white
            4 -> R.drawable.navinfo_icon4_white
            5 -> R.drawable.navinfo_icon5_white
            6 -> R.drawable.navinfo_icon6_white
            7 -> R.drawable.navinfo_icon7_white
            8 -> R.drawable.navinfo_icon8_white
            9 -> R.drawable.navinfo_icon9_white
            10 -> R.drawable.navinfo_icon10_white
            11 -> R.drawable.navinfo_icon11_white
            12 -> R.drawable.navinfo_icon12_white
            13 -> R.drawable.navinfo_icon13_white
            14 -> R.drawable.navinfo_icon14_white
            15 -> R.drawable.navinfo_icon15_white
            16 -> R.drawable.navinfo_icon16_white
            17 -> R.drawable.navinfo_icon17_white
            18 -> R.drawable.navinfo_icon18_white
            19 -> R.drawable.navinfo_icon19_white
            20 -> R.drawable.navinfo_icon20_white
            else -> 0
        }

        /**
         * ICON=17/18 在原 `ic_navi_island` 体系下需水平镜像显示；新 navinfo_icon*
         * 已自带左右区分，本项目保留镜像位标但**不应用**——若新资源后续被发现也要
         * 镜像，调回 true 即可。
         */
        @JvmStatic
        fun turnIconMirrored(icon: Int): Boolean = false

        /**
         * 电子眼类型名：0 测速 1 监控 2 闯红灯 3 违章拍照 4 公交道 5 应急车道
         */
        @JvmStatic
        fun cameraTypeName(type: Int): String = when (type) {
            0 -> "测速"
            1 -> "监控"
            2 -> "闯红灯"
            3 -> "违章拍照"
            4 -> "公交专用"
            5 -> "应急车道"
            else -> "电子眼"
        }
    }

    private var mRegistered = false
    private val mInfo = NaviInfo()
    /** 最近一次收到高德广播的时刻（elapsedRealtime），看门狗判超时用 */
    private var mLastStamp: Long = 0
    private val mHandler = MainThread.handler

    /**
     * 看门狗：非 IDLE 状态下超 [STALE_MS] 无任何广播 → 视为导航/巡航已结束
     * （异常退出收不到 10019 退出广播），走与正常退出一致的清理链路。
     */
    private val mWatchdog = object : Runnable {
        override fun run() {
            if (mRegistered && mInfo.mode != Mode.IDLE
                && SystemClock.elapsedRealtime() - mLastStamp > STALE_MS
            ) {
                reset()
                mListener.onNaviStopped()
            }
            if (mRegistered) {
                mHandler.postDelayed(this, STALE_MS)
            }
        }
    }

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            update(intent)
        }
    }

    fun start() {
        if (mRegistered) return
        val filter = IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND").apply {
            // 高德内部 Widget 广播（限速/电子眼等扩展字段来源，同 amap-companion）
            addAction("AUTO_GUIDE_INFO_FOR_INTERNAL_WIDGET")
            addAction("AUTO_STATUS_FOR_INTERNAL_WIDGET")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_ROAD_NAME_INFO")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_SILENCE_ROADNAME_INFO")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_GPS_INFO")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_CAR_DIRECTION")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_CAMERA_INFO")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_TRAFFIC_LIGHT_INFO")
            addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_CRUISE_TRAFFIC_LIGHT_INFO")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                mContext.registerReceiver(mReceiver, filter)
            }
            mRegistered = true
            mLastStamp = SystemClock.elapsedRealtime()
            mHandler.postDelayed(mWatchdog, STALE_MS)
        } catch (e: Exception) {
            // 静默
        }
    }

    fun stop() {
        mHandler.removeCallbacks(mWatchdog)
        if (mRegistered) {
            try {
                mContext.unregisterReceiver(mReceiver)
            } catch (e: Exception) {
                // 静默
            }
            mRegistered = false
        }
    }

    private fun update(intent: Intent?) {
        if (intent == null || intent.extras == null) return
        // 任意广播到达都证明高德仍在推送，刷新看门狗基点
        mLastStamp = SystemClock.elapsedRealtime()
        when (intent.getIntExtra("KEY_TYPE", 0)) {
            KEY_TYPE_ROUTE -> updateRoute(intent)
            KEY_TYPE_STATE -> updateState(intent.getIntExtra("EXTRA_STATE", -1))
            KEY_TYPE_CRUISE -> updateCruise(intent)
            KEY_TYPE_INTERVAL -> updateInterval(intent)
        }
    }

    /** KEY_TYPE=12110 测速路段：限速的实际有效来源（10001 中恒 -1） */
    private fun updateInterval(intent: Intent) {
        val limit = intent.getIntExtra("LIMITED_SPEED", -1)
        if (limit > 0) mInfo.limitedSpeed = limit
        val type = intent.getIntExtra("CAMERA_TYPE", -1)
        if (type >= 0) mInfo.cameraType = type
        notifyIfActive()
    }

    private fun updateRoute(intent: Intent) {
        mInfo.icon = intent.getIntExtra("ICON", mInfo.icon)
        mInfo.segRemainDis = intent.getIntExtra("SEG_REMAIN_DIS", mInfo.segRemainDis)
        mInfo.remainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", mInfo.remainTime)
        mInfo.remainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", mInfo.remainDis)
        // 限速/电子眼限速：-1 为"无数据"，不覆盖 12110 已设置的有效值
        val limited = intent.getIntExtra("LIMITED_SPEED", mInfo.limitedSpeed)
        if (limited > 0) mInfo.limitedSpeed = limited
        val cameraSpd = intent.getIntExtra("CAMERA_SPEED", mInfo.cameraSpeed)
        if (cameraSpd > 0) mInfo.cameraSpeed = cameraSpd
        mInfo.cameraDist = intent.getIntExtra("CAMERA_DIST", mInfo.cameraDist)
        mInfo.cameraType = intent.getIntExtra("CAMERA_TYPE", mInfo.cameraType)
        // cameraSpeed 上面已用 >0 守护写入；不要直接覆盖（会让 -1 覆盖 12110 的有效值）
        mInfo.sapaDist = intent.getIntExtra("SAPA_DIST", mInfo.sapaDist)
        mInfo.sapaName = stringOr(intent.getStringExtra("SAPA_NAME"), mInfo.sapaName)
        mInfo.endPoiName = stringOr(intent.getStringExtra("endPOIName"), mInfo.endPoiName)
        val curRoad = intent.getStringExtra("CUR_ROAD_NAME")
        if (!curRoad.isNullOrEmpty()) mInfo.curRoadName = curRoad
        mInfo.nextRoadName = intent.getStringExtra("NEXT_ROAD_NAME")
        // TYPE: 0=GPS 导航 1=模拟导航 2=巡航；巡航路线也走 10001 推送
        val cruiseRoute = intent.getIntExtra("TYPE", 0) == 2
        setMode(if (cruiseRoute) Mode.CRUISE else Mode.NAV)
    }

    private fun updateCruise(intent: Intent) {
        val road = intent.getStringExtra("ROAD_NAME")
        if (!road.isNullOrEmpty()) mInfo.curRoadName = road
        val cruiseLimited = intent.getIntExtra("LIMITED_SPEED", mInfo.limitedSpeed)
        if (cruiseLimited > 0) mInfo.limitedSpeed = cruiseLimited
        mInfo.cameraDist = intent.getIntExtra("CAMERA_DIST", mInfo.cameraDist)
        mInfo.cameraType = intent.getIntExtra("CAMERA_TYPE", mInfo.cameraType)
        val cruiseCameraSpd = intent.getIntExtra("CAMERA_SPEED", mInfo.cameraSpeed)
        if (cruiseCameraSpd > 0) mInfo.cameraSpeed = cruiseCameraSpd
        setMode(Mode.CRUISE)
    }

    private fun updateState(state: Int) {
        if (state < 0) return
        when (state) {
            STATE_CRUISE_ENTER -> setMode(Mode.CRUISE)
            STATE_NAV_START -> setMode(Mode.NAV)
            STATE_EXIT_LEGACY, STATE_NAV_EXIT, STATE_NAV_EXIT_ALT, STATE_CRUISE_EXIT -> {
                reset()
                mListener.onNaviStopped()
            }
        }
    }

    private fun setMode(mode: Mode) {
        if (mInfo.mode != mode && mode == Mode.NAV) {
            // 新一轮导航开始时清掉上一轮巡航遗留的电子眼/服务区数据
            mInfo.cameraDist = -1
            mInfo.cameraType = -1
            mInfo.cameraSpeed = -1
            mInfo.sapaDist = -1
            mInfo.sapaName = null
        }
        mInfo.mode = mode
        notifyIfActive()
    }

    private fun reset() {
        mInfo.mode = Mode.IDLE
        mInfo.icon = 0
        mInfo.curRoadName = null
        mInfo.nextRoadName = null
        mInfo.segRemainDis = 0
        mInfo.remainDis = 0
        mInfo.remainTime = 0
        mInfo.limitedSpeed = -1
        mInfo.endPoiName = null
        mInfo.cameraDist = -1
        mInfo.cameraType = -1
        mInfo.cameraSpeed = -1
        mInfo.sapaDist = -1
        mInfo.sapaName = null
    }

    private fun notifyIfActive() {
        if (mInfo.mode != Mode.IDLE && (mInfo.icon > 0 || mInfo.curRoadName != null)) {
            mListener.onNaviInfo(mInfo.copy())
        }
    }

    private fun stringOr(value: String?, fallback: String?): String? =
        if (!value.isNullOrEmpty()) value else fallback
}
