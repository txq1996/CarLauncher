package com.android.launcher37.util
import android.os.Handler
import android.os.Looper

/**
 * 全局主线程 Handler 单例。
 *
 * 项目内所有跨模块主线程调度统一经 [MainThread.handler]（MediaHelper /
 * MusicLauncher / NaviTextClient / PipService / Store / UpdateChecker、
 * AmapNaviListener 等），避免各自实例化 Handler。
 */
object MainThread {
    val handler: Handler = Handler(Looper.getMainLooper())
}
