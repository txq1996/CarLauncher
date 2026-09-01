package com.android.launcher37

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit

/**
 * GPS 车速 IPC 客户端。
 *
 * 协议：
 * 1. `bindService("com.syu.ms.toolkit", pkg="com.syu.ms")` → 获取 `IRemoteToolkit`
 * 2. `toolkit.getRemoteModule(0)` → 获取主模块
 * 3. `module.register(callback, code, 1)` 订阅指定 code
 *
 * 订阅 code：
 * - `U_GPS_SPEED = 0x65` (101)：GPS 车速，ints[0] 为 km/h
 * - `U_ACC_ON = 0x32` (50)：ACC 状态，ints[0]==0 视为 ACC 关 → 归零复位
 *
 * 滤噪：`speed == 1` 强制按 0 显示（避免偶发 1km/h 跳变）。
 *
 * 断线自动重 bind（1s 起指数退避至 30s 封顶；连接成功复位；服务缺失环境不空转）。
 *
 * AIDL 文件：`app/src/main/aidl/com/syu/ipc/`，方法顺序即 transaction 号，不可改动。
 */
class SpeedClient(
    private val mContext: Context,
    private val mListener: Listener
) : ServiceConnection {

    interface Listener {
        fun onSpeedChanged(kmh: Int)
        fun onAccOff()
    }

    companion object {
        /** GPS 车速 code */
        const val CODE_GPS_SPEED = 0x65

        /** ACC 状态 code（0 = ACC 关） */
        const val CODE_ACC_ON = 0x32

        /** 主模块 id */
        private const val MODULE_MAIN = 0

        /** 重绑退避：1s 起指数翻倍至 30s 封顶（连接成功即复位） */
        private const val REBIND_DELAY_MIN_MS = 1000L
        private const val REBIND_DELAY_MAX_MS = 30_000L
    }

    private val mMain = MainThread.handler
    private val mRebind = Runnable { bind() }
    @Volatile private var mBound = false
    @Volatile private var mStopped = true
    private var mRebindDelayMs: Long = REBIND_DELAY_MIN_MS

    private val mCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, flts: FloatArray?, strs: Array<out String>?) {
            if (ints == null || ints.isEmpty()) return
            if (mStopped) return
            if (updateCode == CODE_GPS_SPEED) {
                val speed = if (ints[0] == 1) 0 else ints[0]
                mMain.post {
                    if (!mStopped) mListener.onSpeedChanged(speed)
                }
            } else if (updateCode == CODE_ACC_ON && ints[0] == 0) {
                mMain.post {
                    if (!mStopped) mListener.onAccOff()
                }
            }
        }
    }

    fun start() {
        mStopped = false
        bind()
    }

    fun stop() {
        mStopped = true
        mMain.removeCallbacks(mRebind)
        unbind()
    }

    private fun bind() {
        if (mBound) return
        val intent = Intent("com.syu.ms.toolkit").setPackage("com.syu.ms")
        mBound = try {
            mContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
        if (!mBound) scheduleRebind()
    }

    private fun unbind() {
        if (!mBound) return
        try {
            mContext.unbindService(this)
        } catch (e: Exception) {
            // 静默
        }
        mBound = false
    }

    private fun scheduleRebind() {
        if (mStopped) return
        mMain.removeCallbacks(mRebind)
        mMain.postDelayed(mRebind, mRebindDelayMs)
        mRebindDelayMs = minOf(mRebindDelayMs * 2, REBIND_DELAY_MAX_MS)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        try {
            val toolkit = IRemoteToolkit.Stub.asInterface(service)
            val module = toolkit.getRemoteModule(MODULE_MAIN)
            module.register(mCallback, CODE_GPS_SPEED, 1)
            module.register(mCallback, CODE_ACC_ON, 1)
            // 连接成功复位退避：运行中 ms 重启断连后仍能秒级重试恢复
            mRebindDelayMs = REBIND_DELAY_MIN_MS
        } catch (e: RemoteException) {
            scheduleRebind()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        mBound = false
        scheduleRebind()
    }
}
