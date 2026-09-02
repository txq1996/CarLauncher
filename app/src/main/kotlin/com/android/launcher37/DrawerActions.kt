package com.android.launcher37

import android.content.Context
import android.content.Intent
import android.widget.GridView
import android.widget.Toast

/**
 * 抽屉格子交互（AppDrawer 弹窗与 DrawerOverlay 悬浮窗共用）：
 * "全部应用模式"点击分发、分屏长删、列表异步加载。
 * 底栏选择模式的点击分发由 AppDrawer 自持（依赖其 pickCallback 闭包）。
 */
internal object DrawerActions {

    /**
     * 统一处理"全部应用模式"格子点击。
     * @param dockBtns   当前底栏按钮列表（feat_* 项会ensure入栏并回存）
     * @param onDismiss  关闭抽屉（AppDrawer=popup.dismiss，DrawerOverlay=DrawerOverlay.dismiss）
     * @param onClean    清理动作（AppDrawer 走 cleanFromUi 探测 MediaHelper/PIP；悬浮窗仅 PIP 保护）
     * @param onSplitNew "分屏"入口（AppDrawer 打开侧选器；悬浮窗无 Activity，提示去底栏）
     */
    fun handleNormal(
        c: Context,
        tagStr: String,
        dockBtns: MutableList<Store.V2Button>,
        onDismiss: () -> Unit,
        onClean: () -> Unit,
        onSplitNew: () -> Unit
    ) {
        when {
            tagStr == DrawerAdapter.TAG_SETTINGS -> {
                try {
                    c.startActivity(Intent(c, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
                onDismiss()
            }
            tagStr == DrawerAdapter.TAG_HOME -> {
                DockBar.ensureInDock(dockBtns, Store.V2Button.map("home"))
                Store.saveV2Buttons(c, dockBtns)
                onDismiss(); MapActions.run(c, "home")
            }
            tagStr == DrawerAdapter.TAG_COMPANY -> {
                DockBar.ensureInDock(dockBtns, Store.V2Button.map("company"))
                Store.saveV2Buttons(c, dockBtns)
                onDismiss(); MapActions.run(c, "company")
            }
            tagStr == DrawerAdapter.TAG_CLEAN -> {
                DockBar.ensureInDock(dockBtns, Store.V2Button.clean())
                Store.saveV2Buttons(c, dockBtns)
                onDismiss(); onClean()
            }
            tagStr == DrawerAdapter.TAG_SPLIT_NEW -> onSplitNew()
            tagStr.startsWith(DrawerAdapter.SPLIT_PREFIX) -> {
                val idx = tagStr.substring(DrawerAdapter.SPLIT_PREFIX.length).toIntOrNull()
                    ?: return
                val pair = SplitRepository.get(c, idx)
                if (pair != null) {
                    onDismiss(); Store.launchSplit(c, pair[0], pair[1])
                }
            }
            else -> {
                onDismiss(); Store.launchApp(c, tagStr)
            }
        }
    }

    /** 长按分屏项：删除并异步重建列表 */
    fun removeSplitAndRefresh(c: Context, grid: GridView, idx: Int, dockMode: Boolean, iconSizePx: Int) {
        SplitRepository.remove(c, idx)
        Toast.makeText(c, "已删除分屏项", Toast.LENGTH_SHORT).show()
        loadAdapterAsync(c, grid, dockMode, iconSizePx)
    }

    /** IO 线程加载应用列表并异步设置适配器（[guard] 非空时在回主线程后校验宿主存活） */
    fun loadAdapterAsync(
        c: Context,
        grid: GridView,
        dockMode: Boolean,
        iconSizePx: Int,
        guard: (() -> Boolean)? = null
    ) {
        SharedExecutor.io().execute {
            val adapter = DrawerAdapter(
                c, AppQuery.launcherEntriesSorted(c), Store.v2Buttons(c), dockMode, iconSizePx
            )
            grid.post {
                if (guard == null || guard()) grid.adapter = adapter
            }
        }
    }
}
