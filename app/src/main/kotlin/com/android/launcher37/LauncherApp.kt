package com.android.launcher37
import com.android.launcher37.navi.AmapNaviListener
import com.android.launcher37.navi.NaviSource
import com.android.launcher37.navi.SpeedSource
import com.android.launcher37.music.MediaHub

import android.app.Application

/**
 * Application 单例：进程级共享 client（车速 IPC / 导航文字 / 媒体会话）在此
 * attach 上下文，按监听者数量惰性启停（见各 Hub）。
 */
class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 高德广播全数据监听：与 NaviTextClient 共存（同一 action 由各自 receiver 独立
        // registerReceiver，Android 向所有 receiver 派发）。
        // 负责缓存车速（CUR_SPEED）/红绿灯/路名/限速/转向/电子眼/服务区/TMC/车道线/区间测速
        // 等 NaviTextClient 未覆盖的字段。
        AmapNaviListener.start(this)
        // 多实例 Widget 共享的系统资源客户端：attach 上下文后由首/末监听者惰性启停，
        // 避免同类多实例时重复 bindService / registerReceiver / 轮询
        SpeedSource.attach(this)
        NaviSource.attach(this)
        MediaHub.attach(this)
    }
}
