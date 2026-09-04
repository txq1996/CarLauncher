package com.android.launcher37.home

import android.app.AlertDialog
import android.util.Log
import android.widget.Toast
import com.android.launcher37.data.UpdateChecker

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
                // 紧凑对话框（px 定值）替代系统 AlertDialog 的 sp 大标题/大正文
                val ctx = act
                fun label(text: String, onClick: () -> Unit) = android.widget.Button(ctx).apply {
                    this.text = text
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f)
                    setTextColor(ctx.getColor(com.android.launcher37.R.color.foreground))
                    // 统一按钮样式（与设置页 actionButton 一致）
                    setBackgroundResource(com.android.launcher37.R.drawable.bg_btn)
                    stateListAnimator = null
                    minHeight = 56
                    minimumHeight = 0
                    minimumWidth = 0
                    setPadding(20, 0, 20, 0)
                    setOnClickListener { onClick() }
                }
                var dlg: AlertDialog? = null
                val btnRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                    setPadding(18, 12, 12, 6)
                }
                btnRow.addView(label("稍后") { dlg?.dismiss() })
                btnRow.addView(label("立即更新") {
                    dlg?.dismiss()
                    Toast.makeText(act, "正在后台下载更新…", Toast.LENGTH_SHORT).show()
                    mChecker.confirmUpdate()
                })
                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(android.widget.TextView(ctx).apply {
                        text = "发现新版本 v${info.versionName}"
                        setTextColor(ctx.getColor(com.android.launcher37.R.color.foreground))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f)
                        includeFontPadding = false
                        setPadding(18, 9, 18, 3)
                    })
                    addView(android.widget.TextView(ctx).apply {
                        text = "是否立即下载并安装？$size"
                        setTextColor(ctx.getColor(com.android.launcher37.R.color.foreground))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f)
                        includeFontPadding = false
                        setPadding(18, 12, 18, 0)
                    })
                    addView(btnRow)
                }
                dlg = AlertDialog.Builder(act).setView(root).create()
                dlg.window?.setBackgroundDrawableResource(com.android.launcher37.R.color.surface_highlight)
                dlg.show()
                dlg.window?.setLayout(
                    (act.resources.displayMetrics.widthPixels * 0.5f).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
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
