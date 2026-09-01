@file:Suppress("DEPRECATION")

package com.android.launcher37

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 高德地图广播全数据监听器（车速 / 红绿灯 / 导航文字信息的统一入口）。
 *
 * 监听 `AUTONAVI_STANDARD_BROADCAST_SEND`，全量解析以下 KEY_TYPE：
 * - `10001 (0x2711)` 导航/巡航：转向/路名/距离/限速/电子眼/服务区/红绿灯数/车头方向/出口/车速
 * - `60073 (0xEA79)` 红绿灯：单灯（导航）+ 多灯 JSONArray（巡航），含 STALE 过期回收
 * - `10019 (0x2723)` 昼夜/状态：37=白天 38=夜晚 3=前台 4=后台 9=导航结束 25=巡航结束
 * - `13011 (0x32D7)` TMC 路况 JSON
 * - `13012 (0x32D8)` 车道线 JSON
 * - `12110 (0x2F4E)` 区间测速
 *
 * 数据对外接口：
 * - 静态字段缓存：[lastNaviInfo] / [lastCruiseInfo] / [lastTrafficLight] /
 *   [lastCruiseTrafficLights] / [lastTmcJson] / [lastLaneJson] /
 *   [lastIntervalSpeed] / [dayNightState]
 * - 订阅：[addListener] / [removeListener]，主线程回调（与 NaviTextClient 一致）
 *
 * 与 NaviTextClient 关系：同一广播 action 由各自 receiver 独立 registerReceiver，
 * Android 框架会向所有 receiver 派发同一 intent，因此可共存互不干扰。
 * 本类额外提供 TMC/车道线/区间测速/巡航多灯 JSON，以及车速（CUR_SPEED）和红绿灯全字段。
 *
 * 调试：通过 [AdbDebug] 的 `/dump?kl=...AmapNaviListener` 直接看所有缓存字段与最后一次值。
 */
object AmapNaviListener {

    private const val TAG = "AmapNaviListener"
    private const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"

    /** 红绿灯数据过期回收：原 TrafficLightClient.STALE_MS=2s，无新数据即隐藏。 */
    private const val TRAFFIC_LIGHT_STALE_MS = 2000L

    const val KEY_TYPE_ROUTE = 10001
    const val KEY_TYPE_TRAFFIC_LIGHT = 60073
    const val KEY_TYPE_STATE = 10019
    const val KEY_TYPE_TMC = 13011
    const val KEY_TYPE_LANE = 13012
    const val KEY_TYPE_INTERVAL = 12110

    /** [AmapNaviListener.State.EXTRA_STATE] 取值（与 NaviTextClient 一致语义） */
    object State {
        const val DAY = 37
        const val NIGHT = 38
        const val FOREGROUND = 3
        const val BACKGROUND = 4
        const val NAV_ENDED = 9
        const val CRUISE_ENDED = 25
    }

    /**
     * 10001 导航/巡航数据缓存。`null` 表示尚未收到。
     *
     * 字段命名沿用 Navi-Link AmapNaviReceiver.handleNaviInfo + handleCruiseInfo 的入参，
     * 下游可直接照搬原 Receiver 解析逻辑而不必做字段映射。
     */
    data class NaviInfo(
        /** 转向图标：NEW_ICON 优先缺失回落 ICON；0=巡航 【已用】NaviPanelDelegate:renderKey navi_turn */
        var icon: Int = 0,
        /** 段剩余距离数字部分（去"公里"/"米"）【未用】UI改用 NaviTextClient.segRemainDis，冗余 */
        var segRemainDisNum: String? = null,
        /** 段剩余距离单位"米"/"公里"【未用】同上 */
        var segRemainDisUnit: String? = null,
        /** 当前道路名 CUR_ROAD_NAME【未用】UI以 NaviTextClient.cur/nextRoadName 为主，冗余备份 */
        var curRoadName: String? = null,
        /** 下一道路名 NEXT_ROAD_NAME【未用】同上，UI取 NaviTextClient.nextRoadName */
        var nextRoadName: String? = null,
        /** 全程剩余距离带单位"5.2公里" ROUTE_REMAIN_DIS_AUTO【未用】UI以 NaviTextClient.remainDis 为主 */
        var routeRemainDis: String? = null,
        /** 全程剩余时间带单位"12分钟" ROUTE_REMAIN_TIME_AUTO【未用】同上 */
        var routeRemainTime: String? = null,
        /** 全程总距离(米) ROUTE_ALL_DIS【未用】仅用于 progressPercent 计算，无直接UI */
        var routeAllDis: Int = 0,
        /** 全程剩余距离(米) ROUTE_REMAIN_DIS【未用】同上 */
        var routeRemainDisMeters: Int = 0,
        /** 行程进度0~100 已计算【未用】预留，未上UI */
        var progressPercent: Int = 0,
        /** 当前车速km/h CUR_SPEED【已用】SpeedDelegate:onNaviInfo 回退(IPC不可用时) */
        var curSpeed: Int = 0,
        /** 道路限速km/h LIMITED_SPEED(恒-1需看12110)【未用】UI限速来自 NaviTextClient.limitedSpeed/cameraSpeed，可切换此源 */
        var limitedSpeed: Int = -1,
        /** 电子眼类型0测速1监控2闯红灯3违章4公交5应急【已用】NaviPanelDelegate:renderAlert(巡航/导航) */
        var cameraType: Int = -1,
        /** 电子眼距离(米) CAMERA_DIST【已用】同上 */
        var cameraDist: Int = -1,
        /** 电子眼限速km/h CAMERA_SPEED【已用】同上 */
        var cameraSpeed: Int = -1,
        /** 终点名称 endPOIName【未用】UI以 NaviTextClient.endPoiName 显示 navi_dest，冗余 */
        var endPoiName: String? = null,
        /** 全程红绿灯总数 TRAFFIC_LIGHT_NUM【未用】仅 remainLightNum 上UI */
        var totalLightNum: Int = 0,
        /** 剩余红绿灯数 routeRemainTrafficLightNum【已用】NaviPanelDelegate:navi_light_count */
        var remainLightNum: Int = 0,
        /** 车头方向角度0~360 -1无 CAR_DIRECTION【已用】navi_direction/cruise_direction */
        var carDirection: Int = -1,
        /** 出口名称 EXIT_NAME_INFO【已用】navi_exit */
        var exitName: String? = null,
        /** 出口方向描述 EXIT_DIRECTION_INFO【已用】同上拼接 */
        var exitDirection: String? = null,
        /** 最近服务区名称 SAPA_NAME【未用】UI沿用 NaviTextClient.sapaName，可切换此源(带距离字符串) */
        var sapaName: String? = null,
        /** 最近服务区距离带单位 SAPA_DIST_AUTO【未用】同上 */
        var sapaDist: String? = null,
        /** 服务区类型0/1 SAPA_TYPE【未用】无UI */
        var sapaType: Int = 0,
        /** 下一服务区名称 NEXT_SAPA_NAME【未用】预留未上UI */
        var nextSapaName: String? = null,
        /** 下一服务区距离带单位 NEXT_SAPA_DIST_AUTO【未用】同上 */
        var nextSapaDist: String? = null,
        /** 下一服务区类型 NEXT_SAPA_TYPE【未用】无UI */
        var nextSapaType: Int = 0,
        /** 预计到达文本"18:06"/"明天18:06" ETA_TEXT【已用】navi_eta_text */
        var etaText: String? = null
    )

    /** 巡航专用数据（10001 ICON=0）【全部已用】cruise_road/cruise_alert/cruise_direction + SpeedDelegate车速回退 */
    data class CruiseInfo(
        var curSpeed: Int = 0, // 【已用】SpeedDelegate:onCruiseInfo + cruise_alert
        var curRoadName: String? = null, // 【已用】cruise_road
        var cameraType: Int = 0, // 【已用】cruise_alert
        var cameraSpeed: Int = -1, // 【已用】同上
        var cameraDist: Int = -1, // 【已用】同上
        var carDirection: Int = -1 // 【已用】cruise_direction
    )

    /** 60073 导航单灯红绿灯（cruiseLights缺失时）【已用】SpeedDelegate:traffic 倒计时与颜色 */
    data class TrafficLightInfo(
        var status: Int = 0, // 1红/4绿/其它黄【已用】
        var dir: Int = 4, // 方向【已用】仅取色，UI未显文字
        var countdown: Int = 0 // 倒计时秒【已用】traffic
    )

    /** 12110 区间测速【未用】已解析缓存lastIntervalSpeed，无UI绑定，可用于限速提示 */
    data class IntervalSpeed(
        var startDist: Int = -1, // START_DISTANCE【未用】
        var startDistText: String? = null, // START_DISTANCE_TEXT【未用】
        var avgSpeed: Int = 0, // AVERAGE_SPEED【未用】
        var endDistText: String? = null, // END_DISTANCE_TEXT【未用】
        var limitSpeed: Int = 0 // LIMITED_SPEED区间限速【未用】可替代NaviInfo.limitedSpeed
    )

    /**
     * 订阅接口。各回调均在主线程触发。
     *
     * 实现按需重写关心的方法；未重写的方法走 no-op，不强制空实现。
     */
    interface Listener {
        fun onNaviInfo(info: NaviInfo) {}
        fun onCruiseInfo(info: CruiseInfo) {}
        fun onTrafficLight(info: TrafficLightInfo) {}
        /** [lightsDataJson] = 巡航多灯的原始 JSON 字符串（可能为 null） */
        fun onCruiseTrafficLights(lightsDataJson: String?) {}
        fun onTmcData(tmcJson: String?) {}
        fun onLaneLines(driveWayJson: String?) {}
        fun onIntervalSpeed(info: IntervalSpeed) {}
        /** [isNight] 在 STATE_DAY/STATE_NIGHT 时为非 null，其它状态不回调 */
        fun onDayNightChanged(isNight: Boolean) {}
        fun onAmapForegroundChanged(isForeground: Boolean) {}
        fun onNavigationEnded() {}
        fun onCruiseEnded() {}
        fun onCrossMapStatus(active: Boolean) {}
        /** 简化回调：单个红绿灯过期 / 退出 / 无效数据时触发。 */
        fun onTrafficLightHidden() {}
    }

    // ── 缓存：原子引用写，最后值读取无锁（AdbDebug /dump 可看） ─────
    @Volatile @JvmField var lastNaviInfo: NaviInfo? = null // 【部分已用】见NaviInfo各字段标注
    @Volatile @JvmField var lastCruiseInfo: CruiseInfo? = null // 【已用】巡航全量
    @Volatile @JvmField var lastTrafficLight: TrafficLightInfo? = null // 【已用】单灯
    /** 60073 lightsData 原始JSON（巡航多灯）【未用】已缓存未上UI，可渲染多灯列表 */
    @Volatile @JvmField var lastCruiseTrafficLights: String? = null
    @Volatile @JvmField var lastTmcJson: String? = null // 【未用】EXTRA_TMC_SEGMENT 未上UI
    @Volatile @JvmField var lastLaneJson: String? = null // 【未用】EXTRA_DRIVE_WAY 未上UI
    @Volatile @JvmField var lastIntervalSpeed: IntervalSpeed? = null // 【未用】同上

    /** -1未知 0白天1夜晚2前台3后台 最后10019 EXTRA_STATE【未用】仅存档 */
    @Volatile @JvmField var dayNightState: Int = -1
    @Volatile @JvmField var isNightMode: Boolean = true // 【未用】昼夜切换未接UI
    @Volatile @JvmField var isAmapForeground: Boolean = false // 【未用】前台判断未接UI
    /** 路口放大图1=有 EXTRA_CROSS_MAP【未用】已存档未上UI */
    @Volatile @JvmField var crossMapActive: Boolean = false

    /** 收包总数（包含所有 KEY_TYPE），用于 AdbDebug 健康检查 */
    @Volatile @JvmField var broadcastCount: Long = 0
    @Volatile @JvmField var lastKeyType: Int = 0
    @Volatile @JvmField var lastReceiveAt: Long = 0

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mHandler = MainThread.handler

    @Volatile private var mRegistered = false
    private var mContext: Context? = null

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleIntent(intent)
        }
    }

    /**
     * 启动监听（幂等）。同进程内多次调用只注册一次。
     *
     * 设计成幂等的原因：LauncherApp.onCreate 默认就会调，但 AdbDebug 调试页可能也想直接拉起；
     * 重复 registerReceiver 会抛 IllegalArgumentException，必须先 try/catch。
     */
    @JvmStatic
    fun start(context: Context) {
        if (mRegistered) return
        mContext = context.applicationContext
        val filter = IntentFilter(ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                mContext!!.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                mContext!!.registerReceiver(mReceiver, filter)
            }
            mRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
    }

    /**
     * 停止监听。注销 receiver 并清空注册标记，下一次 [start] 可再次注册。
     */
    @JvmStatic
    fun stop() {
        if (!mRegistered) return
        try {
            mContext?.unregisterReceiver(mReceiver)
        } catch (e: Exception) {
            // 静默
        }
        mRegistered = false
    }

    @JvmStatic
    fun isRunning(): Boolean = mRegistered

    @JvmStatic
    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    @JvmStatic
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != ACTION) return
        val extras = intent.extras ?: return
        val keyType = asInt(extras.get("KEY_TYPE"), 0)
        broadcastCount++
        lastKeyType = keyType
        lastReceiveAt = System.currentTimeMillis()

        when (keyType) {
            KEY_TYPE_STATE -> handleState(extras)
            KEY_TYPE_INTERVAL -> handleInterval(extras)
            KEY_TYPE_TRAFFIC_LIGHT -> handleTrafficLight(extras)
            KEY_TYPE_TMC -> handleTmc(extras)
            KEY_TYPE_LANE -> handleLane(extras)
            KEY_TYPE_ROUTE -> handleRoute(extras)
        }
    }

    private fun handleState(extras: Bundle) {
        val state = asInt(extras.get("EXTRA_STATE"), -1)
        dayNightState = state
        when (state) {
            State.DAY -> {
                isNightMode = false
                post { listeners.forEach { runCatching { it.onDayNightChanged(false) } } }
            }
            State.NIGHT -> {
                isNightMode = true
                post { listeners.forEach { runCatching { it.onDayNightChanged(true) } } }
            }
            State.FOREGROUND -> {
                isAmapForeground = true
                post { listeners.forEach { runCatching { it.onAmapForegroundChanged(true) } } }
            }
            State.BACKGROUND -> {
                isAmapForeground = false
                post { listeners.forEach { runCatching { it.onAmapForegroundChanged(false) } } }
            }
            State.NAV_ENDED -> {
                resetTrafficLight()
                post { listeners.forEach { runCatching { it.onTrafficLightHidden() } } }
                post { listeners.forEach { runCatching { it.onNavigationEnded() } } }
            }
            State.CRUISE_ENDED -> {
                resetTrafficLight()
                post { listeners.forEach { runCatching { it.onTrafficLightHidden() } } }
                post { listeners.forEach { runCatching { it.onCruiseEnded() } } }
            }
        }
        // 路口放大图：1 = 放大图激活
        if (extras.containsKey("EXTRA_CROSS_MAP")) {
            val active = asInt(extras.get("EXTRA_CROSS_MAP"), 0) == 1
            crossMapActive = active
            post { listeners.forEach { runCatching { it.onCrossMapStatus(active) } } }
        }
    }

    private fun handleInterval(extras: Bundle) {
        val startDist = asInt(extras.get("START_DISTANCE"), -1)
        val startDistText = extras.getString("START_DISTANCE_TEXT")
        val avgSpeed = asInt(extras.get("AVERAGE_SPEED"), 0)
        val endDistText = extras.getString("END_DISTANCE_TEXT")
        val limitSpeed = asInt(extras.get("LIMITED_SPEED"), 0)
        val info = IntervalSpeed(startDist, startDistText, avgSpeed, endDistText, limitSpeed)
        lastIntervalSpeed = info
        post { listeners.forEach { runCatching { it.onIntervalSpeed(info) } } }
    }

    private fun handleTrafficLight(extras: Bundle) {
        val status = asInt(extras.get("trafficLightStatus"), 0)
        val dir = asInt(extras.get("dir"), 4)
        val countdown = asInt(extras.get("redLightCountDownSeconds"), 0)
        val lightsData = extras.getString("lightsData")
        // 无效数据：status 与 countdown 都 ≤ 0 → 复位并隐藏，不刷新过期基点
        // （避免退出导航后红绿灯永不消失的实机 bug，与旧 TrafficLightClient 行为一致）
        if (status <= 0 && countdown <= 0) {
            resetTrafficLight()
            post { listeners.forEach { runCatching { it.onTrafficLightHidden() } } }
            return
        }
        lastTrafficLight = TrafficLightInfo(status, dir, countdown)
        // lightsData 非空 = 巡航多灯 JSON；同时也缓存并回调，便于 AdbDebug / 调试页直接看
        if (!lightsData.isNullOrEmpty()) {
            lastCruiseTrafficLights = lightsData
            post { listeners.forEach { runCatching { it.onCruiseTrafficLights(lightsData) } } }
        } else {
            post { listeners.forEach { runCatching { it.onTrafficLight(lastTrafficLight!!) } } }
        }
        // 仅在收到有效灯色数据时刷新过期基点
        scheduleTrafficLightStaleCheck()
    }

    /** 红绿灯过期回收：TRAFFIC_LIGHT_STALE_MS 内未刷新即视为隐藏。 */
    private val mTrafficLightStale = Runnable {
        if (mTrafficLightLastUpdate > 0 &&
            System.currentTimeMillis() - mTrafficLightLastUpdate > TRAFFIC_LIGHT_STALE_MS) {
            resetTrafficLight()
            post { listeners.forEach { runCatching { it.onTrafficLightHidden() } } }
        }
    }

    private fun handleTmc(extras: Bundle) {
        val tmc = extras.getString("EXTRA_TMC_SEGMENT")
        if (tmc != null) {
            lastTmcJson = tmc
            post { listeners.forEach { runCatching { it.onTmcData(tmc) } } }
        }
    }

    private fun handleLane(extras: Bundle) {
        val lane = extras.getString("EXTRA_DRIVE_WAY")
        if (lane != null) {
            lastLaneJson = lane
            post { listeners.forEach { runCatching { it.onLaneLines(lane) } } }
        }
    }

    private fun handleRoute(extras: Bundle) {
        var icon = asInt(extras.get("NEW_ICON"), 0)
        if (icon == 0) icon = asInt(extras.get("ICON"), 0)
        if (icon != 0) {
            lastNaviInfo = parseNaviInfo(extras, icon)
            val info = lastNaviInfo!!
            post { listeners.forEach { runCatching { it.onNaviInfo(info) } } }
        } else {
            // ICON=0 = 巡航数据：单独缓存一份以便 AdbDebug / 上层直接读 speed/road/camera
            val cruise = CruiseInfo(
                curSpeed = asInt(extras.get("CUR_SPEED"), 0),
                curRoadName = extras.getString("CUR_ROAD_NAME") ?: "未知道路",
                cameraType = asInt(extras.get("CAMERA_TYPE"), 0),
                cameraSpeed = asInt(extras.get("CAMERA_SPEED"), -1),
                cameraDist = asInt(extras.get("CAMERA_DIST"), -1),
                carDirection = asInt(extras.get("CAR_DIRECTION"), -1)
            )
            lastCruiseInfo = cruise
            post { listeners.forEach { runCatching { it.onCruiseInfo(cruise) } } }
        }
    }

    private fun parseNaviInfo(extras: Bundle, icon: Int): NaviInfo {
        val routeAllDis = asInt(extras.get("ROUTE_ALL_DIS"), 1)
        val routeRemainDisMeters = asInt(extras.get("ROUTE_REMAIN_DIS"), 0)
        val progress = if (routeAllDis > 0) {
            ((1.0f - routeRemainDisMeters.toFloat() / routeAllDis) * 100).toInt()
        } else 0

        var segRemainDis = extras.getString("SEG_REMAIN_DIS_AUTO") ?: "0米"
        val disUnit: String
        if (segRemainDis.endsWith("公里")) {
            segRemainDis = segRemainDis.removeSuffix("公里")
            disUnit = "公里"
        } else {
            disUnit = "米"
            if (segRemainDis.endsWith("米")) segRemainDis = segRemainDis.removeSuffix("米")
        }
        val curRoadName = extras.getString("CUR_ROAD_NAME")
        val nextRoadName = extras.getString("NEXT_ROAD_NAME") ?: curRoadName ?: "未知道路"

        return NaviInfo(
            icon = icon,
            segRemainDisNum = segRemainDis,
            segRemainDisUnit = disUnit,
            curRoadName = curRoadName,
            nextRoadName = nextRoadName,
            routeRemainDis = extras.getString("ROUTE_REMAIN_DIS_AUTO") ?: "0公里",
            routeRemainTime = extras.getString("ROUTE_REMAIN_TIME_AUTO") ?: "0分钟",
            routeAllDis = routeAllDis,
            routeRemainDisMeters = routeRemainDisMeters,
            progressPercent = progress,
            curSpeed = asInt(extras.get("CUR_SPEED"), 0),
            limitedSpeed = asInt(extras.get("LIMITED_SPEED"), 0),
            cameraType = asInt(extras.get("CAMERA_TYPE"), 0),
            cameraDist = asInt(extras.get("CAMERA_DIST"), 0),
            cameraSpeed = asInt(extras.get("CAMERA_SPEED"), 0),
            endPoiName = extras.getString("endPOIName"),
            totalLightNum = asInt(extras.get("TRAFFIC_LIGHT_NUM"), 0),
            remainLightNum = asInt(extras.get("routeRemainTrafficLightNum"), 0),
            carDirection = asInt(extras.get("CAR_DIRECTION"), -1),
            exitName = extras.getString("EXIT_NAME_INFO"),
            exitDirection = extras.getString("EXIT_DIRECTION_INFO"),
            sapaName = extras.getString("SAPA_NAME"),
            sapaDist = extras.getString("SAPA_DIST_AUTO"),
            sapaType = asInt(extras.get("SAPA_TYPE"), 0),
            nextSapaName = extras.getString("NEXT_SAPA_NAME"),
            nextSapaDist = extras.getString("NEXT_SAPA_DIST_AUTO"),
            nextSapaType = asInt(extras.get("NEXT_SAPA_TYPE"), 0),
            etaText = extras.getString("ETA_TEXT")
        )
    }

    /** 把任务切到主线程（与 NaviTextClient 一致）。仅在已注册时 post。 */
    private fun post(block: () -> Unit) {
        if (mRegistered) mHandler.post(block)
    }

    private var mTrafficLightLastUpdate: Long = 0

    private fun scheduleTrafficLightStaleCheck() {
        mTrafficLightLastUpdate = System.currentTimeMillis()
        mHandler.removeCallbacks(mTrafficLightStale)
        mHandler.postDelayed(mTrafficLightStale, TRAFFIC_LIGHT_STALE_MS)
    }

    /** 清空红绿灯缓存 + 取消过期调度。导航/巡航退出与无效数据复用。 */
    private fun resetTrafficLight() {
        mTrafficLightLastUpdate = 0
        mHandler.removeCallbacks(mTrafficLightStale)
        lastTrafficLight = null
        lastCruiseTrafficLights = null
    }

    /**
     * 通用 "Any → Int" 转换，兼容 Number / 字符串数字 / 数组首元素。
     * 与 NaviLink 原始 Java 版 getIntSafe 一致。
     */
    private fun asInt(v: Any?, def: Int): Int {
        if (v is Number) return v.toInt()
        if (v is String) {
            try {
                return v.toFloat().toInt()
            } catch (_: Exception) {
                return def
            }
        }
        if (v != null && v.javaClass.isArray) {
            val len = java.lang.reflect.Array.getLength(v)
            if (len > 0) {
                val first = java.lang.reflect.Array.get(v, 0)
                if (first is Number) return first.toInt()
            }
            return def
        }
        return def
    }
}