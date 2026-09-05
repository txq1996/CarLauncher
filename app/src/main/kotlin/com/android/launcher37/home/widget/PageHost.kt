package com.android.launcher37.home.widget
import com.android.launcher37.R
import com.android.launcher37.drawer.DrawerOverlay
import com.android.launcher37.data.Store

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.android.launcher37.util.Dbg
import com.android.launcher37.util.Prefs
import com.android.launcher37.SettingsActivity

/**
 * 主页单页容器门面：管理一个 [WidgetHost] + 设计模式 + 命名布局持久化。
 *
 * - 数据：激活的 [NamedLayout]（经典布局为代码默认模板，可修改持久化）→ 创建单个 WidgetHost
 * - 设计模式编辑该页 Widget；工具栏动作在此统一实现；布局增删改/切换在设置页布局管理
 * - 持久化：拖动/缩放/增删等变更经 [persistAll] 只标脏，仅「💾保存」按钮落盘；
 *   未保存退出设计器时从盘上重载丢弃草稿
 * - 聚合生命周期 / VD 查询，供 Store/DrawerOverlay 等无 Activity 调用方使用
 */
class PageHost(
    private val activity: Activity,
    private val container: FrameLayout
) {
    companion object {
        private const val TAG = "PageHost"

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
        findViewById(R.id.btn_design_save)?.setOnClickListener { saveLayout() }
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
        Dbg.i(TAG) { "install designRequested=$designRequested active=${LayoutRepository.activeName(activity)} widgets=${layout.widgets.size} canvas=${screenW()}x${screenH()}" }
        rebuild(layout, designRequested)
    }

    /** 用给定单页布局重建 WidgetHost（打开布局/重置/抽屉切换用）。
     *  重建后统一 startAll：冷启动 onResume 早于装配、切换布局后新 Widget 均需补启动监听。 */
    private fun rebuild(layout: HomeLayout, designRequested: Boolean) {
        Dbg.i(TAG) { "rebuild widgets=${layout.widgets.size} designRequested=$designRequested isDesignMode=$isDesignMode" }
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
        // 布局自带状态栏显隐（每布局独立）：与全局开关不同步时应用
        val sp = Prefs.of(activity)
        if (sp.getBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, false) != layout.hideStatusBar) {
            sp.edit().putBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, layout.hideStatusBar).apply()
            onToggleStatusBar?.invoke()
            container.post { host?.reflow() }
        }
    }

    // ── 生命周期 / 聚合（与旧 WidgetHost 方法名一致） ──

    fun startAll() {
        host?.startAll()
    }

    fun stopAll() {
        host?.stopAll() // 不落盘：未点保存的改动不固化
    }

    fun destroyAll() {
        destroyHost()
        if (instance === this) instance = null
    }

    private fun destroyHost() {
        val h = host
        host = null
        h?.destroyAll() // 不落盘：未点保存的草稿随销毁丢弃
        // 旧 Activity 的 onDestroy 在 CLEAR_TASK 重启时延迟执行（晚于新 Activity 装配）：
        // 仅当 instance 仍指向本 host 时才清空，避免误杀新 Activity 注册的实例
        // （换绑走 WidgetHost.instance.updateConfig，被误清空后静默失效 → 图标不变）
        if (WidgetHost.instance === h) WidgetHost.setInstance(null)
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
        Dbg.i(TAG) { "enterDesignMode" }
        host?.enterDesignMode()
    }

    fun exitDesignMode() {
        if (!isDesignMode) return
        isDesignMode = false
        Dbg.i(TAG) { "exitDesignMode dirty=$mDirty" }
        host?.exitDesignMode() // 内部 save() 标脏
        if (mDirty) {
            // 未点保存退出：丢弃所有更改，从盘上重载激活布局
            mDirty = false
            val doc = LayoutRepository.loadActive(activity, screenW(), screenH())
            val page = doc.pages.firstOrNull()
                ?: HomeLayout(HomeLayout.CURRENT_VERSION, screenW(), screenH(), emptyList())
            Dbg.i(TAG) { "exitDesignMode: discard draft, reload from disk" }
            rebuild(page, false)
        }
        onDesignerExit?.invoke()
        ensureVdLaunched() // 退出设计模式后恢复 VD 显示
    }

    /** 保存按钮：落盘当前布局（内存草稿固化），不退出设计器 */
    fun saveLayout() {
        mDirty = true
        persistNow()
        toast("已保存")
    }

    // ── 持久化 ───────────────────────────────────────

    // 拖动/缩放的高频变更只标脏，不写盘；仅「💾保存」按钮才落盘，其余情况不保存
    private var mDirty = false

    private fun doPersist() {
        val name = LayoutRepository.activeName(activity)
        val layout = host?.snapshotLayout() ?: return
        // 状态栏显隐为全局 SP 运行态，保存布局时一并记录（每布局独立恢复）
        val hide = Prefs.of(activity).getBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, false)
        Dbg.i(TAG) { "doPersist layout=$name widgets=${layout.widgets.size} hideStatusBar=$hide" }
        LayoutRepository.save(activity, NamedLayout(name, listOf(layout.copy(hideStatusBar = hide))))
    }

    /** 布局变更通知（拖动/缩放实时调用）：仅标脏 */
    private fun persistAll() {
        mDirty = true
    }

    /** 保存时落盘（有脏数据才写） */
    fun persistNow() {
        if (!mDirty) return
        mDirty = false
        doPersist()
    }

    // ── 工具栏动作 ───────────────────────────────────

    /** 按名应用布局（布局管理"进入"调用）：置 active + 重建。成功返回 true */
    fun applyLayout(name: String): Boolean {
        val doc = LayoutRepository.load(activity, name, screenW(), screenH())
            ?: return false.also { Dbg.i(TAG) { "applyLayout $name FAILED (not found)" } }
        val page = doc.pages.firstOrNull() ?: return false.also { Dbg.i(TAG) { "applyLayout $name FAILED (empty pages)" } }
        LayoutRepository.setActive(activity, name)
        Dbg.i(TAG) { "applyLayout $name widgets=${page.widgets.size}" }
        rebuild(page, false)
        return true
    }
}
