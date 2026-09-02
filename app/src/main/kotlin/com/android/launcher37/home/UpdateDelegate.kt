package com.android.launcher37.home

import android.util.Log
import android.widget.Toast
import com.android.launcher37.UpdateChecker

/**
 * 更新委派：构造时一次性创建 [UpdateChecker]（持 application，
 * 回调进入弱引用/Activity null-safe），启动时触发自动检查。
 *
 * 关键不变量：UpdateChecker 内部不持 Activity，回调走 null-safe 转发；
 * 自动检查失败仅 Log.w，不打扰用户。
 */
class UpdateDelegate(
    app: android.app.Application,
    private val activity: android.app.Activity?
) {
    private val mChecker: UpdateChecker = UpdateChecker(app, ListenerImpl(activity))

    fun checkOnLaunch() = mChecker.checkOnLaunch()
    fun checkManually() = mChecker.checkManually()
    fun release() = mChecker.release()

    private class ListenerImpl(
        private val activity: android.app.Activity?
    ) : UpdateChecker.Listener {
        override fun onUpdateStart() {}
        override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
            // 自动检查的 onUpdateFound：调 [Activity] 弹 Toast 提示；
            // Activity 已销毁时降级为静默（避免 BadTokenException）。
            val ctx = activity ?: return
            try {
                Toast.makeText(ctx, "发现新版本，正在下载…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("UpdateDelegate", "toast on found failed", e)
            }
        }
        override fun onUpToDate() {}
        override fun onProgress(percent: Int) {}
        override fun onError(message: String) {
            Log.w("UpdateDelegate", "auto update check failed: $message")
        }
    }
}
