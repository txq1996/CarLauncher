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
            tagStr == DrawerAdapter.TAG_GOHOME -> {
                // 返回 launcher37 桌面：launcher 是 HOME，发 HOME intent 即回桌面
                onDismiss()
                try {
                    c.startActivity(Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
            tagStr == DrawerAdapter.TAG_RESTART -> {
                // 重启桌面：与 SettingsActivity.restartLauncher 一致（CLEAR_TASK 重建 LauncherActivity）
                onDismiss()
                try {
                    c.startActivity(Intent(c, LauncherActivity::class.java)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } catch (_: Exception) {}
            }
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
    fun removeSplitAndRefresh(c: Context, grid: GridView, idx: Int, dockMode: Boolean) {
        SplitRepository.remove(c, idx)
        Toast.makeText(c, "已删除分屏项", Toast.LENGTH_SHORT).show()
        loadAdapterAsync(c, grid, dockMode)
    }

    /**
     * IO 线程加载应用列表并异步设置适配器（[guard] 非空时在回主线程后校验宿主存活）。
     * 外观（图标大小/间距/字号）从 SP 读取并应用到 grid（全部应用外观设置，应用 tab）。
     */
    fun loadAdapterAsync(
        c: Context,
        grid: GridView,
        dockMode: Boolean,
        guard: (() -> Boolean)? = null
    ) {
        SharedExecutor.io().execute {
            val p = Prefs.of(c)
            val iconSize = p.getInt(SettingsActivity.KEY_DRAWER_ICON_SIZE, 64)
            val labelSize = p.getInt(SettingsActivity.KEY_DRAWER_LABEL_SIZE, 17)
            val gap = p.getInt(SettingsActivity.KEY_DRAWER_ICON_GAP, 8)
            val adapter = DrawerAdapter(
                c,
                AppQuery.applyDrawerPrefs(c, AppQuery.launcherEntriesSorted(c), dockMode),
                Store.v2Buttons(c), dockMode, iconSize, labelSize
            )
            grid.post {
                grid.horizontalSpacing = gap
                grid.verticalSpacing = gap
                // 自适应列宽：auto_fit 按columnWidth 计算列数，随图标大小联动
                grid.columnWidth = iconSize + 48
                if (guard == null || guard()) grid.adapter = adapter
            }
        }
    }
}
