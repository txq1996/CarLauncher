package com.android.launcher37.drawer
import com.android.launcher37.SettingsActivity
import com.android.launcher37.LauncherActivity
import com.android.launcher37.navi.MapActions
import com.android.launcher37.drawer.DrawerOverlay
import com.android.launcher37.drawer.DrawerAdapter
import com.android.launcher37.data.SplitRepository
import com.android.launcher37.drawer.AppDrawer
import com.android.launcher37.data.Store
import com.android.launcher37.util.Prefs
import com.android.launcher37.util.SharedExecutor
import com.android.launcher37.data.AppQuery
import android.content.Context
import android.content.Intent
import android.widget.GridView
import android.widget.Toast
import com.android.launcher37.home.widget.LayoutRepository
import com.android.launcher37.home.widget.PageHost

/**
 * 抽屉格子交互（AppDrawer 弹窗与 DrawerOverlay 悬浮窗共用）：
 * "全部应用模式"点击分发、分屏长删、列表异步加载。
 */
internal object DrawerActions {

    /**
     * 统一处理"全部应用模式"格子点击。
     * @param onDismiss  关闭抽屉（AppDrawer=popup.dismiss，DrawerOverlay=DrawerOverlay.dismiss）
     * @param onClean    清理动作（AppDrawer 走 cleanFromUi 探测播放中应用/VD；悬浮窗仅 VD 保护）
     * @param onSplitNew "分屏"入口（AppDrawer 打开侧选器；悬浮窗提示去应用列表长按添加）
     */
    fun handleNormal(
        c: Context,
        tagStr: String,
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
                onDismiss(); MapActions.run(c, "home")
            }
            tagStr == DrawerAdapter.TAG_COMPANY -> {
                onDismiss(); MapActions.run(c, "company")
            }
            tagStr == DrawerAdapter.TAG_CLEAN -> {
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
            tagStr.startsWith(DrawerAdapter.LAYOUT_PREFIX) -> {
                // 点击布局图标：切换桌面布局。桌面 Activity 存活时直接重建；
                // 悬浮抽屉路径（桌面已销毁/不在前台）则先置 active 再重建桌面加载
                val name = tagStr.substring(DrawerAdapter.LAYOUT_PREFIX.length)
                onDismiss()
                val ok = PageHost.instance?.applyLayout(name) == true
                if (ok) {
                    Toast.makeText(c, "已切换布局「$name」", Toast.LENGTH_SHORT).show()
                } else {
                    LayoutRepository.setActive(c, name)
                    try {
                        c.startActivity(Intent(c, LauncherActivity::class.java)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    } catch (_: Exception) {}
                    Toast.makeText(c, "将切换布局「$name」", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                onDismiss(); Store.launchApp(c, tagStr)
            }
        }
    }

    /** 长按分屏项：删除并异步重建列表 */
    fun removeSplitAndRefresh(c: Context, grid: GridView, idx: Int) {
        SplitRepository.remove(c, idx)
        Toast.makeText(c, "已删除分屏项", Toast.LENGTH_SHORT).show()
        loadAdapterAsync(c, grid)
    }

    /**
     * IO 线程加载应用列表并异步设置适配器（[guard] 非空时在回主线程后校验宿主存活）。
     * 外观（图标大小/间距/字号）从 SP 读取并应用到 grid（全部应用外观设置，应用 tab）。
     */
    fun loadAdapterAsync(
        c: Context,
        grid: GridView,
        guard: (() -> Boolean)? = null
    ) {
        SharedExecutor.io().execute {
            val p = Prefs.of(c)
            val iconSize = p.getInt(SettingsActivity.KEY_DRAWER_ICON_SIZE, 64)
            val labelSize = p.getInt(SettingsActivity.KEY_DRAWER_LABEL_SIZE, 17)
            val gap = p.getInt(SettingsActivity.KEY_DRAWER_ICON_GAP, 8)
            val adapter = DrawerAdapter(
                c,
                AppQuery.applyDrawerPrefs(c, AppQuery.launcherEntriesSorted(c)),
                iconSize, labelSize
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
