package com.android.launcher37

import android.os.Handler
import android.os.Looper

/**
 * 全局主线程 Handler 单例。
 *
 * 项目内 6 个 Client 各自 `Handler(Looper.getMainLooper())` 实例化（MediaHelper /
 * MusicLauncher / NaviTextClient / PipService / Store / UpdateChecker），以及
 * AmapNaviListener 单例，浪费 ~7 * Handler object header + Looper ref + GC 压力。
 *
 * 统一改用此单例 —— 跨进程 / 跨模块共享，post callback 不变。
 */
object MainThread {
    val handler: Handler = Handler(Looper.getMainLooper())
}
