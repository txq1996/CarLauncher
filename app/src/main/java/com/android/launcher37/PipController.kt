package com.android.launcher37

import android.app.Activity
import android.view.View

/**
 * std PIP 模式：自实现 `ActivityView` 宿主（[MapPipHost]），
 * 把地图任务启动到占位框内的 VirtualDisplay。
 *
 * 关键设计：
 * - **surfaceDestroyed 只摘 surface 绝不 release**：保 display 与任务原位
 *   不动，避免回桌面时任务被清冷启动
 * - **VirtualDisplay 滞后启动**：surfaceChanged 后 500ms 再 startActivity，
 *   避免 TaskDisplayArea 未初始化导致任务回落主屏
 * - **主屏滞留任务搬移**：`moveTaskToDisplay` / `moveRootTaskToDisplay` 反射调用
 * - **flags 分版本**：29+ 用 `TRUSTED | OWN_CONTENT_ONLY`，28 用 0
 */
class PipController(
    private val mActivity: Activity,
    private val mPlaceholder: View
) {
    companion object {
        private const val PIP_PKG_KEY = "persist.launcher.packagename"
    }

    private var mHost: MapPipHost? = null

    /** std 方案：自实现 ActivityView 宿主拉起 */
    fun ensureStd() {
        val pkg = resolvePkg() ?: return
        if (!MapPipHost.available()) return
        var host = mHost
        if (host == null) {
            host = MapPipHost.create(mActivity) ?: return
            host.attach(mPlaceholder as android.view.ViewGroup)
            mHost = host
        }
        host.launch(pkg)
    }

    /** 完整释放（Activity 销毁时调用） */
    fun release() {
        mHost?.release()
        mHost = null
    }

    /**
     * 解析当前 PIP 地图包名。
     *
     * 优先读 `persist.launcher.packagename`（ROM/ms 预置），未安装时回落到
     * [MapApps.detect] 自动探测。
     */
    fun resolvePkg(): String? {
        var pkg = SysProps.get(PIP_PKG_KEY, "")
        if (pkg.isNotEmpty() && !Store.installed(mActivity, pkg)) pkg = ""
        if (pkg.isEmpty()) {
            pkg = MapApps.detect(mActivity) ?: ""
        }
        return pkg.takeIf { it.isNotEmpty() }
    }
}
