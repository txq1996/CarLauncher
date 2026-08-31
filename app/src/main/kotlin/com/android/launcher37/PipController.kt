package com.android.launcher37

import android.content.Context
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
 *
 * [mContext] 使用 applicationContext（构造时调用方传入）。本 controller 在
 * [LauncherApp] 单例存活期间复用，**不能**强引 Activity —— 避免 Activity 销毁
 * 后 controller 仍被回调导致死 Activity 引用。
 */
class PipController(
    private val mContext: Context,
    private var mPlaceholder: View
) {
    companion object {
        private const val PIP_PKG_KEY = "persist.launcher.packagename"
    }

    private var mHost: MapPipHost? = null

    /** 更新 placeholder 视图（Activity 重建时调用） */
    fun setPlaceholder(view: View) {
        mPlaceholder = view
        mHost?.let { it.attach(view as android.view.ViewGroup) }
    }

    /** std 方案：自实现 ActivityView 宿主拉起 */
    fun ensureStd() {
        val pkg = resolvePkg() ?: return
        if (!MapPipHost.available()) return
        var host = mHost
        if (host == null) {
            host = MapPipHost.create(mContext) ?: return
            host.attach(mPlaceholder as android.view.ViewGroup)
            mHost = host
        }
        host.launch(pkg)
    }

    /**
     * 释放瞬时资源但**不**销毁 VirtualDisplay。
     * 用于 Activity 销毁 / APK 自更新 / 进程被杀重启：导航任务留在
     * VD 上，新进程 attach surface 后能继续显示。
     */
    fun releaseTransient() {
        mHost?.releaseTransient()
        // mHost 保留：mVd 仍持有 VirtualDisplay 句柄；下次 ensureStd 时
        // 复用同 VD、只需 attach 新 surface。
    }

    /**
     * 完整释放（同时销毁 VirtualDisplay）。仅在用户/系统明确关闭 PIP 时使用。
     */
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
        if (pkg.isNotEmpty() && !Store.installed(mContext, pkg)) pkg = ""
        if (pkg.isEmpty()) {
            pkg = MapApps.detect(mContext) ?: ""
        }
        return pkg.takeIf { it.isNotEmpty() }
    }
}
