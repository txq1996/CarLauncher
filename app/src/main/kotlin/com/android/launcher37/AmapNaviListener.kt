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
 * 高德地图广播全数据监听器。
 *
 * 监听 `AUTONAVI_STANDARD_BROADCAST_SEND`，全量解析以下 KEY_TYPE：
 * - `10001 (0x2711)` 导航/巡航：转向/路名/距离/限速/电子眼/服务区/红绿灯数/车头方向/出口
 * - `60073 (0xEA79)` 红绿灯：单灯（导航）+ 多灯 JSONArray（巡航）
 * - `10019 (0x2723)` 昼夜/状态：37=白天 38=夜晚 3=前台 4=后台 9=导航结束 25=巡航结束
 * - `13011 (0x32D7)` TMC 路况 JSON
 * - `13012 (0x32D8)` 车道线 JSON
 * - `12110 (0x2F4E)` 区间测速
 *
 * 数据对外接口：
 * - 静态字段缓存：[lastNaviInfo] / [lastCruiseInfo] / [lastTrafficLight] /
 *   [lastCruiseTrafficLights] / [lastTmcJson] / [lastLaneJson] /
 *   [lastIntervalSpeed] / [dayNightState]
 * - 订阅：[addListener] / [removeListener]，主线程回调（与 TrafficLightClient/NaviTextClient 一致）
 *
 * 与已有 NaviTextClient / TrafficLightClient 关系：同一广播 action 由各自 receiver 独立
 * registerReceiver，Android 框架会向所有 receiver 派发同一 intent，因此可共存互不干扰。
 * 本类是它们的"全字段汇总"，对外提供未在它们里暴露的 TMC/车道线/区间测速/巡航多灯 JSON。
 *
 * 调试：通过 [AdbDebug] 的 `/dump?kl=...AmapNaviListener` 直接看所有缓存字段与最后一次值。
 */
object AmapNaviListener {

    private const val TAG = "AmapNaviListener"
    private const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"

    const val KEY_TYPE_ROUTE = 10001
    const val KEY_TYPE_TRAFFIC_LIGHT = 60073
    const val KEY_TYPE_STATE = 10019
    const val KEY_TYPE_TMC = 13011
    const val KEY_TYPE_LANE = 13012
    const val KEY_TYPE_INTERVAL = 12110

    /** [AmapNaviListener.State.EXTRA_STATE] 取值（与 TrafficLightClient/NaviTextClient 一致语义） */
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
        /** 转向图标：NEW_ICON 优先，缺失回落 ICON；0 = 巡航（无转向） */
        var icon: Int = 0,
        /** 段剩余距离数字部分（去掉了"公里"/"米"后缀） */
        var segRemainDisNum: String? = null,
        /** 段剩余距离单位："米" / "公里" */
        var segRemainDisUnit: String? = null,
        /** 当前道路名（来自 CUR_ROAD_NAME） */
        var curRoadName: String? = null,
        /** 下一道路名（来自 NEXT_ROAD_NAME，缺失回落 CUR_ROAD_NAME） */
        var nextRoadName: String? = null,
        /** 全程剩余距离（含单位原始字符串，如"5.2公里"） */
        var routeRemainDis: String? = null,
        /** 全程剩余时间（含单位原始字符串，如"12分钟"） */
        var routeRemainTime: String? = null,
        /** 全程总距离（米，ROUTE_ALL_DIS） */
        var routeAllDis: Int = 0,
        /** 全程剩余距离（米，ROUTE_REMAIN_DIS） */
        var routeRemainDisMeters: Int = 0,
        /** 行程进度 0~100，由 routeRemainDis/routeAllDis 推算 */
        var progressPercent: Int = 0,
        /** 当前车速 km/h（CUR_SPEED） */
        var curSpeed: Int = 0,
        /** 道路限速 km/h（LIMITED_SPEED，恒 -1 见 12110 来源） */
        var limitedSpeed: Int = -1,
        /** 电子眼类型 0=测速 1=监控 2=闯红灯 3=违章拍照 4=公交道 5=应急车道 */
        var cameraType: Int = -1,
        /** 电子眼距离（米，缺失为 -1） */
        var cameraDist: Int = -1,
        /** 电子眼限速 km/h（缺失为 -1） */
        var cameraSpeed: Int = -1,
        /** 终点名称 */
        var endPoiName: String? = null,
        /** 全程红绿灯总数 */
        var totalLightNum: Int = 0,
        /** 剩余红绿灯数 */
        var remainLightNum: Int = 0,
        /** 车头方向角度（0~360，-1 表示无） */
        var carDirection: Int = -1,
        /** 出口名称 */
        var exitName: String? = null,
        /** 出口方向描述 */
        var exitDirection: String? = null,
        /** 最近服务区名称 */
        var sapaName: String? = null,
        /** 最近服务区距离（含单位） */
        var sapaDist: String? = null,
        /** 服务区类型（0/1） */
        var sapaType: Int = 0,
        /** 下一服务区名称 */
        var nextSapaName: String? = null,
        /** 下一服务区距离（含单位） */
        var nextSapaDist: String? = null,
        /** 下一服务区类型 */
        var nextSapaType: Int = 0,
        /** 预计到达文本（"18:06" / "明天18:06"） */
        var etaText: String? = null
    )

    /** 巡航专用数据（来自 10001 ICON=0 时的解析，结构与 [NaviInfo] 子集重叠但单独缓存） */
    data class CruiseInfo(
        var curSpeed: Int = 0,
        var curRoadName: String? = null,
        var cameraType: Int = 0,
        var cameraSpeed: Int = -1,
        var cameraDist: Int = -1,
        var carDirection: Int = -1
    )

    /** 60073 导航单灯红绿灯（cruiseLights 缺失时） */
    data class TrafficLightInfo(
        var status: Int = 0,
        var dir: Int = 4,
        var countdown: Int = 0
    )

    /** 12110 区间测速 */
    data class IntervalSpeed(
        var startDist: Int = -1,
        var startDistText: String? = null,
        var avgSpeed: Int = 0,
        var endDistText: String? = null,
        var limitSpeed: Int = 0
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
    }

    // ── 缓存：原子引用写，最后值读取无锁 ─────────────────────────────

    @Volatile @JvmField var lastNaviInfo: NaviInfo? = null
    @Volatile @JvmField var lastCruiseInfo: CruiseInfo? = null
    @Volatile @JvmField var lastTrafficLight: TrafficLightInfo? = null
    /** 60073 lightsData 的原始 JSON 字符串（巡航多灯）；null 表示无 */
    @Volatile @JvmField var lastCruiseTrafficLights: String? = null
    @Volatile @JvmField var lastTmcJson: String? = null
    @Volatile @JvmField var lastLaneJson: String? = null
    @Volatile @JvmField var lastIntervalSpeed: IntervalSpeed? = null

    /** -1=未知；0=白天 1=夜晚 2=前台 3=后台（最后一次 10019 EXTRA_STATE 缓存） */
    @Volatile @JvmField var dayNightState: Int = -1
    @Volatile @JvmField var isNightMode: Boolean = true
    @Volatile @JvmField var isAmapForeground: Boolean = false
    /** 路口放大图状态：true=有（EXTRA_CROSS_MAP=1） */
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
            State.NAV_ENDED -> post { listeners.forEach { runCatching { it.onNavigationEnded() } } }
            State.CRUISE_ENDED -> post { listeners.forEach { runCatching { it.onCruiseEnded() } } }
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
        lastTrafficLight = TrafficLightInfo(status, dir, countdown)
        // lightsData 非空 = 巡航多灯 JSON；同时也缓存并回调，便于 AdbDebug / 调试页直接看
        if (!lightsData.isNullOrEmpty()) {
            lastCruiseTrafficLights = lightsData
            post { listeners.forEach { runCatching { it.onCruiseTrafficLights(lightsData) } } }
        } else {
            post { listeners.forEach { runCatching { it.onTrafficLight(lastTrafficLight!!) } } }
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

    /** 把任务切到主线程（与 TrafficLightClient/NaviTextClient 一致）。仅在已注册时 post。 */
    private fun post(block: () -> Unit) {
        if (mRegistered) mHandler.post(block)
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