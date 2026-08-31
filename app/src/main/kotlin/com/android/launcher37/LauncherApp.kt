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
        // ADB 调试入口默认开启，开关关闭时 [AdbDebug.tryStartIfEnabled] 不会起 HTTP
        AdbDebug.tryStartIfEnabled(this)
    }
}
