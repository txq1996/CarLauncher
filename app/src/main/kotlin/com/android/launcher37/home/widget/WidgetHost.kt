package com.android.launcher37.home.widget

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * 主页 Widget 容器（单页）：加载布局 → 创建 Widget → 绝对定位装配 → 设计模式 → 保存。
 *
 * - 由 [PageHost] 按页创建/持有；[install] 接收页布局 [HomeLayout] 并创建全部 Widget
 *   （允许同类型多实例）
 * - 生命周期转发：start/stop/destroy/onThemeChange/ensureLaunched
 * - 设计模式：切换 widget.designMode；spec 修改（拖动/缩放/显隐/删除/添加）
 *   由 [DesignerController] 调 updateRect/updateConfig 等方法，实时应用 + 经
 *   [onLayoutChanged] 通知 PageHost 全量持久化
 * - 静态 instance：供 WidgetView.designMode 拿到统一的拖动手势入口；
 *   PageHost 切换设计目标页时通过 [setAsDesignTarget] 维护
 */
class WidgetHost private constructor(
    private val activity: Activity,
    private val container: FrameLayout
) {
    companion object {
        /** 当前设计目标 host（设计器/Widget 手势回调用；PageHost 维护） */
        @Volatile
        var instance: WidgetHost? = null
            private set

        fun create(activity: Activity, container: FrameLayout): WidgetHost {
            // 多页共存：不再销毁既有 host（由 PageHost 统一管理生命周期）
            return WidgetHost(activity, container)
        }

        internal fun setInstance(host: WidgetHost?) {
            instance = host
        }
    }

    /** id → widget（插入顺序 = 绘制层级） */
    private val widgets = LinkedHashMap<Int, WidgetView>()
    private var designer: DesignerController? = null
    private var mNextId = 1
    private var mDesignRequested = false

    /** 布局变更通知（拖/缩放/显隐/增删/属性）→ PageHost 全量持久化 */
    var onLayoutChanged: (() -> Unit)? = null

    /** widget 拖动手势（设计模式时 WidgetView.setOnTouchListener 使用） */
    val widgetTouchListener = View.OnTouchListener { v, ev ->
        designer?.onWidgetTouch(v, ev) ?: false
    }

    val isDesignMode: Boolean get() = designer != null
    val designerView: View? get() = designer?.selectionOverlay
    internal val currentDesigner: DesignerController? get() = designer

    /** 设计器退出后的回调（Activity 恢复状态栏/隐藏工具栏） */
    var onDesignerExit: (() -> Unit)? = null

    /** 屏幕尺寸（坐标系）：容器铺满全屏，直接取容器宽高 */
    fun screenW(): Int = max(container.width, 1)
    fun screenH(): Int = max(container.height, 1)

    // ── 装配 ─────────────────────────────────────────

    /** 加载页布局并创建全部 Widget（由 PageHost 在容器测量完成后调用）。
     *  sw/sh 为已知屏幕尺寸（page 刚 addView 尚未测量，container.width=0，
     *  直接用容器宽高 normalize 会把全部部件钳到 1×1，故由 PageHost 传入真实尺寸）。
     *  [designRequested] 为 true 表示进入设计器即装配：VD 不挂 surface 不拉起应用。 */
    fun install(layout: HomeLayout, sw: Int = screenW(), sh: Int = screenH(), designRequested: Boolean = false) {
        mDesignRequested = designRequested
        val norm = LayoutRepository.normalize(layout, sw, sh)
        mNextId = (norm.widgets.maxOfOrNull { it.id } ?: 0) + 1
        for (spec in norm.widgets) {
            createWidget(spec)
        }
    }

    private fun createWidget(spec: WidgetSpec): WidgetView? {
        if (widgets.containsKey(spec.id)) return null
        val w = when (spec.type) {
            WidgetTypes.TIME -> TimeWidget(activity, spec)
            WidgetTypes.LYRICS -> LyricsWidget(activity, spec)
            WidgetTypes.SPEED -> SpeedWidget(activity, spec)
            WidgetTypes.VD -> VdWidget(activity, spec)
            WidgetTypes.APPLIST -> AppListWidget(activity, spec)
            else -> return null
        }
        // 设计模式下装配（进入设计器/设计模式内打开布局）：VD 保持休眠不挂 surface
        if (designer != null || mDesignRequested) w.designMode = true
        w.bind()
        container.addView(w)
        widgets[spec.id] = w
        return w
    }

    internal fun snapshotLayout(): HomeLayout {
        val list = widgets.values.map { it.spec }
        return HomeLayout(HomeLayout.CURRENT_VERSION, screenW(), screenH(), list)
    }

    // ── 生命周期转发 ─────────────────────────────────

    fun startAll() {
        for (w in widgets.values) if (w.spec.visible) w.start()
    }

    fun stopAll() {
        for (w in widgets.values) w.stop()
    }

    fun destroyAll() {
        designer?.detach()
        designer = null
        for (w in widgets.values) w.destroy()
        widgets.clear()
        container.removeAllViews()
    }

    fun onThemeChange() {
        for (w in widgets.values) w.onThemeChange()
    }

    /** 窗口焦点就绪后拉起 VD（带 pip_start_delay 延迟，原 PIP 时序语义） */
    fun ensureVdLaunched() {
        val delayMs = VdWidget.startDelayMs(activity)
        val runnable = Runnable {
            for (w in widgets.values) if (w is VdWidget && w.spec.visible) w.ensureLaunched()
        }
        if (delayMs > 0) container.postDelayed(runnable, delayMs.toLong())
        else container.post(runnable)
    }

    // ── 设计模式 ─────────────────────────────────────

    fun enterDesignMode() {
        if (designer != null) return
        // 保持 start 状态：设计器实时预览实际数据（时间走秒/车速/歌词/VD 画面）
        // 仅拦截触摸（widget.designMode），不停止业务
        for (w in widgets.values) w.designMode = true
        designer = DesignerController(activity, this, container).also { it.onExit = { onDesignerExit?.invoke() } }
    }

    /** 退出设计模式：保存布局 → 恢复正常运行（widget 实时预览无需重载） */
    fun exitDesignMode() {
        val d = designer ?: return
        designer = null
        d.detach()
        save()
        for (w in widgets.values) {
            w.designMode = false
            // 退出设计模式时隐藏的 widget 恢复可见由 spec 决定（visible=false 直接 GONE）
            w.applySpec()
        }
        startAll()
    }

    /**
     * 设计模式内切页用的轻量切换：只切 widget.designMode + 建/拆 designer，
     * 不触发 startAll/stopAll/save（避免 Music 重复注册 receiver、VD 时序抖动）。
     * 由 PageHost 在切设计目标页时调用。
     */
    internal fun setDesignModeActive(active: Boolean) {
        if (active) {
            if (designer == null) {
                for (w in widgets.values) w.designMode = true
                designer = DesignerController(activity, this, container)
                    .also { it.onExit = { onDesignerExit?.invoke() } }
            }
        } else {
            designer?.detach()
            designer = null
            for (w in widgets.values) w.designMode = false
        }
    }

    /** 画布尺寸变化（状态栏切换）后重放全部 widget 到新边界 */
    internal fun reflow() {
        for (w in widgets.values) {
            w.spec = clampSpec(w.spec)
            w.applySpec()
        }
    }

    private fun clampSpec(s: WidgetSpec): WidgetSpec {
        val sw = screenW(); val sh = screenH()
        val w = widgets[s.id]
        val minW = w?.minSizeW() ?: LayoutRepository.MIN_SIZE
        val minH = w?.minSizeH() ?: LayoutRepository.MIN_SIZE
        val cw = s.w.coerceIn(minW, sw)
        val ch = s.h.coerceIn(minH, sh)
        return s.copy(
            x = s.x.coerceIn(0, sw - cw),
            y = s.y.coerceIn(0, sh - ch),
            w = cw, h = ch
        )
    }

    // ── spec 修改（设计器调用；实时应用 + 通知持久化） ─────

    fun widgetAt(id: Int): WidgetView? = widgets[id]

    fun allWidgets(): List<WidgetView> = widgets.values.toList()

    fun updateRect(id: Int, x: Int, y: Int, w: Int, h: Int) {
        val widget = widgets[id] ?: return
        widget.spec = clampSpec(widget.spec.copy(x = x, y = y, w = w, h = h))
        widget.applySpec()
        save()
    }

    fun updateConfig(id: Int, key: String, value: String) {
        val widget = widgets[id] ?: return
        // 同页同 App 禁止重复绑定：忽略本次修改
        if (key == CFG_VD_PKG && isVdPkgTaken(value, excludeId = id)) return
        widget.spec = widget.spec.copy(config = widget.spec.config + (key to value))
        widget.onPropChanged(key, value)
        if (widget is VdWidget) widget.onAppChanged()
        save()
    }

    /** 本页是否已有其他 VD 绑定 [pkg]（同页同 App 禁止重复） */
    fun isVdPkgTaken(pkg: String?, excludeId: Int = -1): Boolean {
        if (pkg.isNullOrBlank()) return false
        return widgets.values.any { it is VdWidget && it.spec.id != excludeId && it.boundPkg == pkg }
    }

    /** 添加 Widget（默认尺寸放画布中央，选中返回设计器）；VD 需 [vdPkg] 显式绑定 */
    fun addWidget(type: String, vdPkg: String? = null): WidgetView? {
        // 同页同 App 禁止重复：被占用则拒绝添加
        if (type == WidgetTypes.VD && isVdPkgTaken(vdPkg)) return null
        val id = mNextId++
        val sw = screenW(); val sh = screenH()
        val w = (sw * 0.33f).toInt().coerceAtLeast(LayoutRepository.MIN_SIZE * 4)
        val h = (sh * 0.3f).toInt().coerceAtLeast(LayoutRepository.MIN_SIZE * 4)
        val spec = WidgetSpec(
            id = id, type = type,
            x = (sw - w) / 2, y = (sh - h) / 2, w = w, h = h,
            visible = true,
            config = if (type == WidgetTypes.VD && !vdPkg.isNullOrBlank()) {
                mapOf(CFG_VD_PKG to vdPkg)
            } else emptyMap()
        )
        val widget = createWidget(spec) ?: return null
        save()
        return widget
    }

    /** 删除 Widget（VD 先搬回任务）；返回是否成功 */
    fun removeWidget(id: Int): Boolean {
        val widget = widgets.remove(id) ?: return false
        if (widget is VdWidget) widget.removeWithTaskRecovery()
        else widget.destroy()
        container.removeView(widget)
        save()
        return true
    }

    /** 布局变更通知（持久化由 PageHost 全量执行） */
    fun save() {
        onLayoutChanged?.invoke()
    }

    // ── 外部查询（内存清理 / 全屏搬移） ───────────────

    /** 全部 VDWidget 绑定的包名（内存清理保护集合） */
    fun vdBoundedPkgs(): Set<String> =
        widgets.values.filterIsInstance<VdWidget>().mapNotNull { it.boundPkg }.toSet()

    /** 暂停绑定 [pkg] 的 VD（切页时清空其他页同 App VD，避免其抢回同一任务） */
    fun clearVdByPkg(pkg: String) {
        for (w in widgets.values) if (w is VdWidget && w.boundPkg == pkg) {
            w.pauseVd()
        }
    }

    /**
     * 把绑定 [pkg] 的 VD 任务搬回主屏全屏（dock/抽屉点击 VD 同款 App 时走此路径，
     * 原 PipController.expandToFullscreen 语义）。成功返回 true。
     */
    fun expandVdToFullscreen(pkg: String): Boolean {
        for (w in widgets.values) {
            if (w is VdWidget && w.boundPkg == pkg && w.moveTaskToMainScreen()) return true
        }
        return false
    }
}
