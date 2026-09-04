package com.android.launcher37.home.widget
import com.android.launcher37.R
import com.android.launcher37.util.Dbg

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
 *   PageHost 在创建/销毁 host 时通过 [setInstance] 维护
 */
class WidgetHost private constructor(
    private val activity: Activity,
    private val container: FrameLayout
) {
    companion object {
        private const val TAG = "WidgetHost"

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
    /** 当前布局的设计参数：控件间距 / 屏幕边距（install 时读入） */
    private var mGap = HomeLayout.DEFAULT_GAP
    private var mMargin = 0

    /** 布局变更通知（拖/缩放/显隐/增删/属性）→ PageHost 全量持久化 */
    var onLayoutChanged: (() -> Unit)? = null

    /** widget 拖动手势（设计模式时 WidgetView.setOnTouchListener 使用） */
    val widgetTouchListener = View.OnTouchListener { v, ev ->
        designer?.onWidgetTouch(v, ev) ?: false
    }

    internal val currentDesigner: DesignerController? get() = designer

    /** 设计器退出后的回调（Activity 恢复状态栏/隐藏工具栏） */
    var onDesignerExit: (() -> Unit)? = null

    /** 屏幕尺寸（坐标系）：容器铺满全屏，直接取容器宽高 */
    fun screenW(): Int = max(container.width, 1)
    fun screenH(): Int = max(container.height, 1)

    /** 当前布局的屏幕边距（设计器缩放尺寸上限用） */
    internal val margin: Int get() = mMargin

    /** 其他 Widget 矩形列表（设计器碰撞检测/添加避让用，已膨胀间距 [mGap]） */
    fun otherRects(excludeId: Int): List<android.graphics.Rect> {
        val g = mGap
        return widgets.values.filter { it.spec.id != excludeId }.map {
            android.graphics.Rect(it.spec.x - g, it.spec.y - g, it.spec.x + it.spec.w + g, it.spec.y + it.spec.h + g)
        }
    }

    /** 目标矩形是否与现有其他 Widget 冲突（含布局间距） */
    fun collides(id: Int, x: Int, y: Int, w: Int, h: Int): Boolean {
        val r = android.graphics.Rect(x, y, x + w, y + h)
        return otherRects(id).any { r.intersects(it.left, it.top, it.right, it.bottom) }
    }

    /** 水平缩放碰撞：仅 x 轴膨胀间距 [mGap]，y 轴用真实矩形（垂直方向的间距不足不误判水平缩放） */
    fun collidesH(id: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        collidesAxis(id, x, y, w, h, inflateX = true, inflateY = false)

    /** 垂直缩放碰撞：仅 y 轴膨胀间距 [mGap]，x 轴用真实矩形（水平方向的间距不足不误判垂直缩放） */
    fun collidesV(id: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        collidesAxis(id, x, y, w, h, inflateX = false, inflateY = true)

    /** 单/双轴碰撞检测核心：按需选择哪一轴膨胀间距 */
    private fun collidesAxis(
        id: Int, x: Int, y: Int, w: Int, h: Int, inflateX: Boolean, inflateY: Boolean
    ): Boolean {
        val gx = if (inflateX) mGap else 0
        val gy = if (inflateY) mGap else 0
        val r = android.graphics.Rect(x, y, x + w, y + h)
        return widgets.values.filter { it.spec.id != id }.any {
            android.graphics.Rect(
                it.spec.x - gx, it.spec.y - gy,
                it.spec.x + it.spec.w + gx, it.spec.y + it.spec.h + gy
            ).intersects(r.left, r.top, r.right, r.bottom)
        }
    }

    /** 目标矩形是否与现有其他 Widget 真实相交（不含间距膨胀）：
     *  仅真实压住才允许自由移动/缩放解脱，间距不足（软违反）不放宽限制 */
    fun overlaps(id: Int, x: Int, y: Int, w: Int, h: Int): Boolean {
        val r = android.graphics.Rect(x, y, x + w, y + h)
        return widgets.values.filter { it.spec.id != id }.any {
            r.intersects(it.spec.x, it.spec.y, it.spec.x + it.spec.w, it.spec.y + it.spec.h)
        }
    }

    // ── 装配 ─────────────────────────────────────────

    /** 加载页布局并创建全部 Widget（由 PageHost 在容器测量完成后调用）。
     *  sw/sh 为已知屏幕尺寸（page 刚 addView 尚未测量，container.width=0，
     *  直接用容器宽高 normalize 会把全部部件钳到 1×1，故由 PageHost 传入真实尺寸）。
     *  [designRequested] 为 true 表示进入设计器即装配：VD 不挂 surface 不拉起应用。 */
    fun install(layout: HomeLayout, sw: Int = screenW(), sh: Int = screenH(), designRequested: Boolean = false) {
        mDesignRequested = designRequested
        mGap = layout.gap.coerceAtLeast(0)
        mMargin = layout.margin.coerceAtLeast(0)
        val norm = LayoutRepository.normalize(layout, sw, sh)
        mNextId = (norm.widgets.maxOfOrNull { it.id } ?: 0) + 1
        Dbg.i(TAG) { "install sw=$sw sh=$sh gap=$mGap margin=$mMargin designRequested=$designRequested widgets=${norm.widgets.size} types=${norm.widgets.joinToString { it.type }}" }
        for (spec in norm.widgets) {
            // 装配后按布局边距/边界钳制一次，保证屏幕边距对已保存布局立即生效
            val w = createWidget(spec) ?: continue
            w.spec = clampSpec(w.spec)
            w.applySpec()
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
        return HomeLayout(HomeLayout.CURRENT_VERSION, screenW(), screenH(), list, mGap, mMargin)
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
        for (w in widgets.values) {
            w.onThemeChange()
            w.refreshDesignLabelTheme()
        }
    }

    /** 窗口焦点就绪后拉起 VD（带 pip_start_delay 延迟，原 PIP 时序语义） */
    fun ensureVdLaunched() {
        val delayMs = VdWidget.startDelayMs(activity)
        val runnable = Runnable {
            for (w in widgets.values) if (w is VdWidget && w.spec.visible) w.ensureLaunched()
        }
        Dbg.i(TAG) { "ensureVdLaunched delay=${delayMs}ms vdCount=${widgets.values.count { it is VdWidget }}" }
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

    /** 退出设计模式：恢复正常运行（widget 实时预览无需重载）。
     *  注意：不在此 save() —— 设计期间每条修改路径（updateRect/updateConfig/增删）
     *  均已实时标脏，此处再标会令"无修改退出"也被 PageHost 判为脏而全量重建。 */
    fun exitDesignMode() {
        val d = designer ?: return
        Dbg.i(TAG) { "exitDesignMode (widgets=${widgets.size})" }
        designer = null
        d.detach()
        for (w in widgets.values) {
            w.designMode = false
            // 退出设计模式时隐藏的 widget 恢复可见由 spec 决定（visible=false 直接 GONE）
            w.applySpec()
        }
        startAll()
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
        val widget = widgets[s.id]
        val minW = widget?.minSizeW() ?: LayoutRepository.MIN_SIZE
        val minH = widget?.minSizeH() ?: LayoutRepository.MIN_SIZE
        // 屏幕边距（每布局独立）：控件四边距画布边缘 mMargin
        val cw = s.w.coerceIn(minW, (sw - 2 * mMargin).coerceAtLeast(minW))
        val ch = s.h.coerceIn(minH, (sh - 2 * mMargin).coerceAtLeast(minH))
        val loX = mMargin.coerceAtMost(sw - cw)
        val hiX = (sw - cw - mMargin).coerceAtLeast(loX)
        val loY = mMargin.coerceAtMost(sh - ch)
        val hiY = (sh - ch - mMargin).coerceAtLeast(loY)
        return s.copy(
            x = s.x.coerceIn(loX, hiX),
            y = s.y.coerceIn(loY, hiY),
            w = cw, h = ch
        )
    }

    // ── spec 修改（设计器调用；实时应用 + 通知持久化） ─────

    fun widgetAt(id: Int): WidgetView? = widgets[id]

    fun allWidgets(): List<WidgetView> = widgets.values.toList()

    fun updateRect(id: Int, x: Int, y: Int, w: Int, h: Int) {
        val widget = widgets[id] ?: return
        Dbg.d(TAG) { "updateRect id=$id (${widget.spec.x},${widget.spec.y} ${widget.spec.w}x${widget.spec.h}) -> ($x,$y ${w}x$h)" }
        widget.spec = clampSpec(widget.spec.copy(x = x, y = y, w = w, h = h))
        widget.applySpec()
        save()
    }

    fun updateConfig(id: Int, key: String, value: String) {
        val widget = widgets[id] ?: return
        // 同页同 App 禁止重复绑定：忽略本次修改
        if (key == CFG_VD_PKG && isVdPkgTaken(value, excludeId = id)) {
            Dbg.i(TAG) { "updateConfig REJECT dup VD pkg=$value id=$id" }
            return
        }
        Dbg.d(TAG) { "updateConfig id=$id $key=$value" }
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

    /** 添加 Widget（自动寻找不与现有控件重叠的位置，找不到则回落画布中央）；VD 需 [vdPkg] 显式绑定 */
    fun addWidget(type: String, vdPkg: String? = null): WidgetView? {
        // 同页同 App 禁止重复：被占用则拒绝添加
        if (type == WidgetTypes.VD && isVdPkgTaken(vdPkg)) return null
        val id = mNextId++
        val sw = screenW(); val sh = screenH()
        val w = (sw * 0.33f).toInt().coerceAtLeast(LayoutRepository.MIN_SIZE * 4)
        val h = (sh * 0.3f).toInt().coerceAtLeast(LayoutRepository.MIN_SIZE * 4)
        val (px, py) = findFreeSpot(w, h, sw, sh)
        val spec = WidgetSpec(
            id = id, type = type,
            x = px, y = py, w = w, h = h,
            visible = true,
            config = if (type == WidgetTypes.VD && !vdPkg.isNullOrBlank()) {
                mapOf(CFG_VD_PKG to vdPkg)
            } else emptyMap()
        )
        val widget = createWidget(spec) ?: return null
        save()
        Dbg.i(TAG) { "addWidget type=$type id=$id vdPkg=$vdPkg at=($px,$py ${w}x$h)" }
        return widget
    }

    /** 网格扫描第一个不与现有控件（含布局间距）重叠的摆放点；找不到回落画布中央 */
    private fun findFreeSpot(w: Int, h: Int, sw: Int, sh: Int): Pair<Int, Int> {
        val step = 20
        val lo = mMargin
        var y = lo
        while (y + h <= sh - mMargin) {
            var x = lo
            while (x + w <= sw - mMargin) {
                if (!collides(-1, x, y, w, h)) return x to y
                x += step
            }
            y += step
        }
        return ((sw - w) / 2) to ((sh - h) / 2)
    }

    /** 删除 Widget（VD 先搬回任务）；返回是否成功 */
    fun removeWidget(id: Int): Boolean {
        val widget = widgets.remove(id) ?: return false
        Dbg.i(TAG) { "removeWidget id=$id type=${widget.displayName}" }
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
