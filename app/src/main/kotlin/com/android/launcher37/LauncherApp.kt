package com.android.launcher37

import android.app.Application

/**
 * Application 单例：跨 Activity 持有 [PipController]。
 *
 * 关键作用：让 VirtualDisplay 句柄与 mSurfaceView 不被 Activity 销毁/重建抹掉，
 * 进程内自更新 / onCreate/onDestroy 切换时导航任务可继续显示。
 * 跨进程（系统杀）时由系统依据 VirtualDisplay 标志（TRUSTED + OWN_CONTENT_ONLY）
 * 在 surfaceDestroyed 时仅摘 surface，display 留在系统池里。
 */
class LauncherApp : Application() {
    var pipController: PipController? = null

    override fun onCreate() {
        super.onCreate()
        // 高德广播全数据监听：与 NaviTextClient 共存（同一 action 由各自 receiver 独立
        // registerReceiver，Android 向所有 receiver 派发）。
        // 负责缓存车速（CUR_SPEED）/红绿灯/路名/限速/转向/电子眼/服务区/TMC/车道线/区间测速
        // 等 NaviTextClient 未覆盖的字段。
        AmapNaviListener.start(this)
    }
}
