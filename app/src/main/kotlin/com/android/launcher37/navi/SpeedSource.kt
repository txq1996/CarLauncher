package com.android.launcher37.navi
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GPS 车速共享源：单例包装一个 [SpeedClient]（bindService com.syu.ms），
 * 多个 SpeedWidget 实例共用同一连接，避免重复 bind / 重复 IPC。
 *
 * - [LauncherApp.onCreate] 调 [attach] 提供上下文
 * - 首个监听者加入时启动 client，最后一个移除时停止（与 Widget start/stop 配对）
 * - 回调在主线程触发（SpeedClient 内部已 post）
 */
object SpeedSource : SpeedClient.Listener {

    private val listeners = CopyOnWriteArrayList<SpeedClient.Listener>()

    @Volatile private var mContext: Context? = null
    @Volatile private var mClient: SpeedClient? = null

    fun attach(context: Context) {
        mContext = context.applicationContext
    }

    fun addListener(l: SpeedClient.Listener) {
        if (listeners.addIfAbsent(l)) ensureStarted()
    }

    fun removeListener(l: SpeedClient.Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) {
            mClient?.stop()
            mClient = null
        }
    }

    private fun ensureStarted() {
        val c = mContext ?: return
        if (mClient == null && listeners.isNotEmpty()) {
            mClient = SpeedClient(c, this).also { it.start() }
        }
    }

    override fun onSpeedChanged(kmh: Int) {
        for (l in listeners) runCatching { l.onSpeedChanged(kmh) }
    }

    override fun onAccOff() {
        for (l in listeners) runCatching { l.onAccOff() }
    }
}
