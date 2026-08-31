package com.android.launcher37

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler

/**
 * 高德导航红绿灯广播客户端。
 *
 * 监听高德标准广播，解析灯向（dir）、状态与倒计时；数据超过 2 秒未更新回调隐藏。
 *
 * dir 语义（同高德 `CameraLightInfo.direction`）：
 * - 0=掉头 1=左转 2/3=右转 4=直行 5/6=左前斜向 7/8=右前斜向
 * - 缺省 -1 表示由调用方按当前转向推算
 *
 * 关键行为：
 * - 有效性过滤：`status<=0 且 countdown<=0` 视为无灯数据，**复位数据且不刷新过期基点**，
 *   避免退出导航后红绿灯永不消失的实机 bug
 * - 退出即隐：`NAVIGATION_STOPPED` 广播或 10019 状态值 0/9/12/25 立即复位
 * - 跨 stop/start 残留清理：launcher 不可见期间 receiver 已注销，回桌面时补一次过期检查
 * - 超时/无效/退出三条隐藏路径均同步复位内部数据
 */
class TrafficLightClient(
    private val mContext: Context,
    private val mListener: Listener
) {
    interface Listener {
        fun onTrafficLight(dir: Int, status: Int, countdown: Int)
        fun onTrafficLightHidden()
    }

    companion object {
        private const val STALE_MS = 2000L
        private const val ACTION_NAV_STOPPED = "com.autonavi.navigation.NAVIGATION_STOPPED"
        // 高德 10019 导航状态广播（同 NaviTextClient 语义）：0/9/12/25 = 退出
        private const val KEY_TYPE_STATE = 0x2723
    }

    private val mHandler = MainThread.handler
    private var mRegistered = false
    private var mLastUpdate: Long = 0
    private var mDir: Int = -1
    private var mStatus: Int = -1
    private var mCountdown: Int = -1

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            update(intent)
        }
    }

    /**
     * 单次过期检查（广播驱动）：收到红绿灯数据时调度，超时未刷新则回调隐藏。
     * 相比固定间隔轮询，无数据期间主线程零唤醒（同 NaviTextClient 看门狗模式）。
     */
    private val mStaleChecker = Runnable {
        if (mLastUpdate > 0 && System.currentTimeMillis() - mLastUpdate > STALE_MS) {
            resetData()
            mListener.onTrafficLightHidden()
        }
    }

    fun start() {
        if (mRegistered) return
        val filter = IntentFilter().apply {
            addAction("com.autonavi.navigation.NAVIGATION_UPDATES")
            addAction(ACTION_NAV_STOPPED)
            addAction("gaode_navigation_activated")
            addAction("AUTONAVI_STANDARD_BROADCAST_SEND")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                mContext.registerReceiver(mReceiver, filter)
            }
            mRegistered = true
        } catch (e: Exception) {
            // 静默
        }
        // 跨 stop/start 残留清理：launcher 不可见期间导航已退出（receiver 注销
        // 收不到退出广播），回桌面时补一次过期检查
        if (mRegistered && mLastUpdate > 0) {
            mHandler.removeCallbacks(mStaleChecker)
            mHandler.post(mStaleChecker)
        }
    }

    fun stop() {
        if (mRegistered) {
            try {
                mContext.unregisterReceiver(mReceiver)
            } catch (e: Exception) {
                // 静默
            }
            mRegistered = false
        }
        mHandler.removeCallbacks(mStaleChecker)
    }

    /** 复位全部数据与过期基点（隐藏后状态清零，下次广播按新数据处理） */
    private fun resetData() {
        mLastUpdate = 0
        mDir = -1
        mStatus = -1
        mCountdown = -1
        mHandler.removeCallbacks(mStaleChecker)
    }

    private fun update(intent: Intent?) {
        if (intent == null || intent.extras == null) return
        val action = intent.action
        // 导航退出广播：立即隐藏复位
        if (ACTION_NAV_STOPPED == action) {
            resetData()
            mListener.onTrafficLightHidden()
            return
        }
        val ex: Bundle = intent.extras ?: return
        if (ex.getInt("KEY_TYPE", 0) == KEY_TYPE_STATE) {
            val state = ex.getInt("EXTRA_STATE", -1)
            if (state == 0 || state == 9 || state == 12 || state == 25) {
                resetData()
                mListener.onTrafficLightHidden()
                return
            }
        }
        // containsKey 只查键表不触发 value unparcel，仅对关心的字段 get 一次
        var changed = false
        changed = readIntField(ex, "redLightCountDownSeconds", mCountdown) { mCountdown = it } || changed
        // 绿灯尾计数：仅当红灯倒计时缺席时作为倒计时来源
        if (!changed) {
            val n = asInt(ex.get("greenLightLastSecond"), -1)
            if (n > 0 && mCountdown <= 0) { mCountdown = n; changed = true }
        }
        changed = readIntField(ex, "trafficLightStatus", mStatus) { mStatus = it } || changed
        val dirKey = when {
            ex.containsKey("dir") -> "dir"
            ex.containsKey("direction") -> "direction"
            else -> null
        }
        if (dirKey != null) {
            val n = asInt(ex.get(dirKey), mDir)
            if (n != mDir) { mDir = n; changed = true }
        }
        if (mStatus > 0 || mCountdown > 0) {
            // 有效灯色数据：显示并刷新过期基点（仅 status 键到场才续命，
            // 与原逻辑一致；dir 单独变化的广播不延长超时）
            if (ex.containsKey("trafficLightStatus")) {
                mLastUpdate = System.currentTimeMillis()
                mHandler.removeCallbacks(mStaleChecker)
                mHandler.postDelayed(mStaleChecker, STALE_MS)
            }
            if (changed) mListener.onTrafficLight(mDir, mStatus, mCountdown)
        } else if (changed) {
            // 无灯数据（高德巡航/待机时恒发 status=0、countdown=0）：
            // 隐藏复位而非显示黄灯 0 秒，且不刷新过期基点
            resetData()
            mListener.onTrafficLightHidden()
        }
    }

    private fun asInt(v: Any?, def: Int): Int {
        if (v is Number) return v.toInt()
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

    /** 读 [ex] 中的 [key] 字段：与 [current] 不同时写入 [assign] 并返回 true */
    private inline fun readIntField(
        ex: Bundle,
        key: String,
        current: Int,
        assign: (Int) -> Unit
    ): Boolean {
        if (!ex.containsKey(key)) return false
        val n = asInt(ex.get(key), current)
        if (n == current) return false
        assign(n)
        return true
    }
}
