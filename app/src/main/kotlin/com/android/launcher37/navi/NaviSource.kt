package com.android.launcher37.navi
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 高德导航文字信息共享源：单例包装一个 [NaviTextClient]（registerReceiver），
 * 多个 SpeedWidget 实例共用同一 receiver，避免同一广播被重复解析 N 份。
 *
 * - [LauncherApp.onCreate] 调 [attach] 提供上下文
 * - 首个监听者加入时注册，最后一个移除时注销（与 Widget start/stop 配对）
 * - 回调在主线程触发（NaviTextClient 的 receiver 回调即主线程）
 */
object NaviSource : NaviTextClient.Listener {

    private val listeners = CopyOnWriteArrayList<NaviTextClient.Listener>()

    @Volatile private var mContext: Context? = null
    @Volatile private var mClient: NaviTextClient? = null

    fun attach(context: Context) {
        mContext = context.applicationContext
    }

    fun addListener(l: NaviTextClient.Listener) {
        if (listeners.addIfAbsent(l)) ensureStarted()
    }

    fun removeListener(l: NaviTextClient.Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) {
            mClient?.stop()
            mClient = null
        }
    }

    private fun ensureStarted() {
        val c = mContext ?: return
        if (mClient == null && listeners.isNotEmpty()) {
            mClient = NaviTextClient(c, this).also { it.start() }
        }
    }

    override fun onNaviInfo(info: NaviTextClient.NaviInfo) {
        for (l in listeners) runCatching { l.onNaviInfo(info.copy()) }
    }

    override fun onNaviStopped() {
        for (l in listeners) runCatching { l.onNaviStopped() }
    }
}
