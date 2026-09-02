package com.android.launcher37.home

import android.app.AlertDialog
import android.util.Log
import android.widget.Toast
import com.android.launcher37.UpdateChecker

/**
 * 更新委派：构造时一次性创建 [UpdateChecker]（持 application，
 * 回调进入弱引用/Activity null-safe），启动时触发自动检查。
 *
 * 关键不变量：UpdateChecker 内部不持 Activity，回调走 null-safe 转发；
 * 自动检查失败仅 Log.w，不打扰用户。
 *
 * 确认流程：发现新版本时弹窗（立即更新/稍后），用户确认后才调
 * [UpdateChecker.confirmUpdate] 开始下载安装；Activity 已销毁时降级为静默。
 */
class UpdateDelegate(
    app: android.app.Application,
    private val activity: android.app.Activity?
) {
    private val mChecker: UpdateChecker = UpdateChecker(app, object : UpdateChecker.Listener {
        override fun onUpdateStart() {}
        override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
            val act = activity ?: return
            try {
                val size = if (info.sizeBytes > 0) "\n大小：%.1f MB".format(info.sizeBytes / 1048576f) else ""
                AlertDialog.Builder(act)
                    .setTitle("发现新版本 v${info.versionName}")
                    .setMessage("是否立即下载并安装？$size")
                    .setPositiveButton("立即更新") { _, _ ->
                        Toast.makeText(act, "正在后台下载更新…", Toast.LENGTH_SHORT).show()
                        mChecker.confirmUpdate()
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            } catch (e: Exception) {
                Log.w("UpdateDelegate", "update confirm dialog failed", e)
            }
        }
        override fun onUpToDate() {}
        override fun onProgress(percent: Int) {}
        override fun onError(message: String) {
            Log.w("UpdateDelegate", "auto update check failed: $message")
        }
    })

    fun checkOnLaunch() = mChecker.checkOnLaunch()
    fun checkManually() = mChecker.checkManually()
    fun release() = mChecker.release()
}
