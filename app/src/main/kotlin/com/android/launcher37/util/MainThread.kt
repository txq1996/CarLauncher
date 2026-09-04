package com.android.launcher37.util
import com.android.launcher37.navi.AmapNaviListener
import com.android.launcher37.pip.PipService
import com.android.launcher37.data.UpdateChecker
import com.android.launcher37.navi.NaviTextClient
import com.android.launcher37.music.MediaHelper
import com.android.launcher37.music.MusicLauncher
import com.android.launcher37.data.Store
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
