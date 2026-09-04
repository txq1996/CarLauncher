package com.android.launcher37
import com.android.launcher37.navi.AmapNaviListener
import com.android.launcher37.drawer.DrawerOverlay
import com.android.launcher37.navi.NaviTextClient
import com.android.launcher37.data.Store
import com.android.launcher37.data.MemoryCleaner

import android.app.Application
import com.android.launcher37.home.widget.PageHost

/**
 * Application 单例：跨 Activity 持有主页 [PageHost]。
 *
 * 关键作用：Store.launchApp / MemoryCleaner / DrawerOverlay 等无 Activity 上下文
 * 的调用方可查询 VDWidget 绑定的包名（内存清理保护 / VD 任务搬回主屏全屏）。
 * Activity onCreate 设置、onDestroy 清空。
 */
class LauncherApp : Application() {
    var activeHost: PageHost? = null

    override fun onCreate() {
        super.onCreate()
        // 高德广播全数据监听：与 NaviTextClient 共存（同一 action 由各自 receiver 独立
        // registerReceiver，Android 向所有 receiver 派发）。
        // 负责缓存车速（CUR_SPEED）/红绿灯/路名/限速/转向/电子眼/服务区/TMC/车道线/区间测速
        // 等 NaviTextClient 未覆盖的字段。
        AmapNaviListener.start(this)
    }
}
