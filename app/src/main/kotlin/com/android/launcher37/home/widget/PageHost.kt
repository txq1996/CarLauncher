package com.android.launcher37.home.widget

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.android.launcher37.Prefs
import com.android.launcher37.R
import com.android.launcher37.SettingsActivity

/**
 * 主页单页容器门面：管理一个 [WidgetHost] + 设计模式 + 命名布局持久化。
 *
 * - 数据：激活的 [NamedLayout]（经典布局为代码默认模板，可修改持久化，可重置）→ 创建单个 WidgetHost
 * - 设计模式编辑该页 Widget；工具栏动作在此统一实现
 * - 所有自动持久化统一经 [persistAll]（退出设计模式/拖动缩放实时写盘）
 * - 聚合生命周期 / VD 查询，方法名与旧 [WidgetHost] 一致，Store/DrawerOverlay 调用点免改
 */
class PageHost(
    private val activity: Activity,
    private val container: FrameLayout
) {
    companion object {
        /** 当前活跃 PageHost（Activity 销毁时置 null） */
        @Volatile
        var instance: PageHost? = null
            private set
    }

    private var host: WidgetHost? = null

    var isDesignMode: Boolean = false
        private set
    var onDesignerExit: (() -> Unit)? = null
    var onToggleStatusBar: (() -> Unit)? = null

    init {
        instance = this
        bindToolbar()
    }

    private fun bindToolbar() {
        findViewById(R.id.btn_design_add)?.setOnClickListener { host?.currentDesigner?.showAddDialog() }
        findViewById(R.id.btn_design_del)?.setOnClickListener { host?.currentDesigner?.deleteSelected() }
        findViewById(R.id.btn_design_props)?.setOnClickListener { host?.currentDesigner?.showPropsDialog() }
        findViewById(R.id.btn_design_status)?.setOnClickListener { onToggleStatusBar() }
        findViewById(R.id.btn_design_open)?.setOnClickListener { onOpenLayout() }
        findViewById(R.id.btn_design_reset)?.setOnClickListener { onResetLayout() }
        findViewById(R.id.btn_design_save)?.setOnClickListener { onSaveLayout() }
        findViewById(R.id.btn_design_done)?.setOnClickListener { exitDesignMode() }
    }

    private fun findViewById(id: Int): View? = activity.findViewById(id)

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    // ── 装配 ─────────────────────────────────────────

    fun screenW(): Int = maxOf(container.width, 1)
    fun screenH(): Int = maxOf(container.height, 1)

    /** 加载激活布局并创建 WidgetHost（容器测量完成后由 Activity 调用）。
     *  [designRequested] 为 true 表示进入设计器即装配：VD 不挂 surface 不拉起应用。 */
    fun install(designRequested: Boolean = false) {
        val doc = LayoutRepository.loadActive(activity, screenW(), screenH())
        val layout = doc.pages.firstOrNull()
            ?: HomeLayout(HomeLayout.CURRENT_VERSION, screenW(), screenH(), emptyList())
        rebuild(layout, designRequested)
    }

    /** 用给定单页布局重建 WidgetHost（打开布局/重置/抽屉切换用）。
     *  重建后统一 startAll：冷启动 onResume 早于装配、切换布局后新 Widget 均需补启动监听。 */
    private fun rebuild(layout: HomeLayout, designRequested: Boolean) {
        destroyHost()
        val h = WidgetHost.create(activity, container)
        h.onLayoutChanged = { persistAll() }
        h.onDesignerExit = { onDesignerExit?.invoke() }
        h.install(layout, screenW(), screenH(), designRequested || isDesignMode)
        host = h
        WidgetHost.setInstance(h)
        // 设计模式内重建（重置/打开布局）：为新 host 补建设计器
        if (isDesignMode) h.enterDesignMode()
        // 音乐/车速等业务监听随装配启动（start/stop 与 Activity 生命周期配对）
        h.startAll()
    }

    // ── 生命周期 / 聚合（与旧 WidgetHost 方法名一致） ──

    fun startAll() {
        host?.startAll()
    }

    fun stopAll() {
        host?.stopAll()
    }

    fun destroyAll() {
        destroyHost()
        if (instance === this) instance = null
    }

    private fun destroyHost() {
        host?.destroyAll()
        host = null
        WidgetHost.setInstance(null)
    }

    fun onThemeChange() {
        host?.onThemeChange()
    }

    fun ensureVdLaunched() {
        if (isDesignMode) return
        host?.ensureVdLaunched()
    }

    fun allWidgets(): List<WidgetView> = host?.allWidgets() ?: emptyList()

    fun vdBoundedPkgs(): Set<String> = host?.vdBoundedPkgs() ?: emptySet()

    fun expandVdToFullscreen(pkg: String): Boolean = host?.expandVdToFullscreen(pkg) ?: false

    // ── 设计模式 ─────────────────────────────────────

    fun enterDesignMode() {
        if (isDesignMode) return
        isDesignMode = true
        host?.enterDesignMode()
    }

    fun exitDesignMode() {
        if (!isDesignMode) return
        isDesignMode = false
        host?.exitDesignMode() // 内部 save → persistAll（内置只读自动丢弃）
        onDesignerExit?.invoke()
        ensureVdLaunched() // 退出设计模式后恢复 VD 显示
    }

    // ── 持久化 ───────────────────────────────────────

    private fun persistAll() {
        val name = LayoutRepository.activeName(activity)
        val layout = host?.snapshotLayout() ?: return
        LayoutRepository.save(activity, NamedLayout(name, listOf(layout)))
    }

    // ── 工具栏动作 ───────────────────────────────────

    /** 另存为：命名对话框（预填当前布局名，改名即另存，同名覆盖） */
    fun onSaveLayout() {
        val input = EditText(activity).apply {
            hint = "布局名称"
            setText(LayoutRepository.activeName(activity))
            requestFocus()
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("另存为新布局（同名覆盖）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("名称不能为空")
                    return@setPositiveButton
                }
                val existing = LayoutRepository.listNames(activity)
                    .any { it == name && it != LayoutRepository.activeName(activity) }
                if (existing) confirmOverwrite(name) else doSave(name)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // 弹软键盘让输入框可编辑（不弹则无法输入）
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun confirmOverwrite(name: String) {
        AlertDialog.Builder(activity)
            .setTitle("覆盖布局")
            .setMessage("已存在同名布局「$name」，是否覆盖？")
            .setPositiveButton("覆盖") { _, _ -> doSave(name) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSave(name: String) {
        val layout = host?.snapshotLayout() ?: return
        LayoutRepository.save(activity, NamedLayout(name, listOf(layout)))
        LayoutRepository.setActive(activity, name)
        toast("已保存「$name」")
        exitDesignMode()
    }

    /** 重置：清除经典布局的持久化修改，恢复出厂模板并激活 */
    fun onResetLayout() {
        AlertDialog.Builder(activity)
            .setTitle("重置布局")
            .setMessage("将清除经典布局的全部修改，恢复默认布局？")
            .setPositiveButton("重置") { _, _ ->
                LayoutRepository.delete(activity, LayoutRepository.BUILTIN_NAME)
                LayoutRepository.setActive(activity, LayoutRepository.BUILTIN_NAME)
                applyLayout(LayoutRepository.BUILTIN_NAME)
                toast("已重置为默认布局")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按名应用布局（抽屉布局图标 / 打开布局 / 重置共用）：置 active + 重建。成功返回 true */
    fun applyLayout(name: String): Boolean {
        val doc = LayoutRepository.load(activity, name, screenW(), screenH())
            ?: return false
        val page = doc.pages.firstOrNull() ?: return false
        LayoutRepository.setActive(activity, name)
        rebuild(page, false)
        return true
    }

    /** 打开：列出全部布局，选择后重建 */
    fun onOpenLayout() {
        val names = LayoutRepository.listNames(activity)
        AlertDialog.Builder(activity)
            .setTitle("打开布局")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                AlertDialog.Builder(activity)
                    .setTitle("打开「$name」")
                    .setMessage("将替换当前桌面布局？")
                    .setPositiveButton("打开") { _, _ ->
                        if (applyLayout(name)) toast("已打开「$name」")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 状态栏开关：翻转 SP 并回调 Activity 应用，随后 reflow 到新边界 */
    fun onToggleStatusBar() {
        val cur = Prefs.of(activity).getBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, false)
        Prefs.of(activity).edit().putBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, !cur).apply()
        onToggleStatusBar?.invoke()
        container.post { host?.reflow() }
        refreshStatusButton()
    }

    fun refreshStatusButton() {
        val hide = Prefs.of(activity).getBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, false)
        (findViewById(R.id.btn_design_status) as? android.widget.Button)?.text =
            if (hide) "状态栏：隐藏" else "状态栏：显示"
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
