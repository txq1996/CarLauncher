package com.android.launcher37.home.widget

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.android.launcher37.R

/**
 * 主页 Widget 基类：绝对定位容器（[WidgetHost] 的画布直接 addView）。
 *
 * - 内容布局由子类构造参数 layoutRes 提供，inflate 后铺满容器（尺寸 = [WidgetSpec.w/h]）
 * - 生命周期：[start]/[stop] 跟随 Activity onStart/onStop，[destroy] 跟随 onDestroy；
 *   [ensureLaunched] 由 Activity 首次获得窗口焦点后调用（VDWidget 拉起）
 * - 设计模式（[designMode]）：onInterceptTouchEvent 拦截全部触摸，内部按钮/列表
 *   失效；容器事件交给设计器（点击=选中，拖动=移动）。退出设计模式恢复原 listener。
 *
 * 卡片底色统一圆角 surface（与原 LayoutDelegate.applyTheme 一致）；
 * 无卡片底的子类（Dock）自行清除。
 */
abstract class WidgetView(
    protected val activity: Activity,
    @JvmField internal var spec: WidgetSpec,
    layoutRes: Int
) : FrameLayout(activity) {

    /** 类型显示名（设计器列表） */
    abstract val displayName: String

    /** 可编辑属性（设计器"属性"面板按此渲染；默认无） */
    open val props: List<WidgetProp> = emptyList()

    /** 属性变更回调（config 已更新；子类按需重渲染） */
    open fun onPropChanged(key: String, value: String) {}

    // ── config 读取辅助（缺省回落；设计器面板同包访问） ─────────────

    internal fun cfg(key: String, def: String): String = spec.config[key] ?: def
    internal fun cfgInt(key: String, def: Int): Int = spec.config[key]?.toIntOrNull() ?: def
    internal fun cfgBool(key: String, def: Boolean): Boolean =
        spec.config[key]?.let { it == "1" || it.equals("true", true) } ?: def

    /** inflate 完成后的内容根 view（= 容器第一个子 view） */
    protected lateinit var content: View
        private set

    /** 设计模式：拦截所有触摸事件（子 view 收不到），容器自身消费供设计器拖动 */
    open var designMode: Boolean = false
        set(value) {
            field = value
            isClickable = value
            if (value) setOnTouchListener(WidgetHost.instance?.widgetTouchListener)
            else setOnTouchListener(null)
            onDesignModeChanged(value)
        }

    /** 设计模式切换钩子（子类按需隐藏 SurfaceView 等独立图层，见 VdWidget） */
    protected open fun onDesignModeChanged(design: Boolean) {}

    /** 设计器缩放/拖动的允许最小宽（缺省 MIN_SIZE；VD 覆盖为屏幕 20%） */
    open fun minSizeW(): Int = LayoutRepository.MIN_SIZE
    open fun minSizeH(): Int = LayoutRepository.MIN_SIZE

    init {
        // layoutRes 走构造参数：父类 init 早于子类属性初始化，abstract val 会读到 0。
        // 注意 inflate(..., attachToRoot=true) 返回值是 root 本身，content 必须取实际子 view。
        activity.layoutInflater.inflate(layoutRes, this, true)
        content = getChildAt(0)
        content.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
    }

    /**
     * 内容绑定（findViewById / listener / 背景），**构造完成后**由 [WidgetHost] 调用
     * （父类 init 阶段子类属性尚未初始化，禁止在构造里调用）。
     */
    internal fun bind() {
        onBind()
        applySpec()
    }

    /** 内容绑定实现（子类在构造完成后一次性绑定） */
    protected abstract fun onBind()

    /** 卡片底：圆角 surface；withCard=false 时清除（Dock 无卡底） */
    protected fun setCardBackground(withCard: Boolean) {
        background = if (withCard) GradientDrawable().apply {
            cornerRadius = 12f
            setColor(resources.getColor(R.color.surface, context.theme))
        } else null
    }

    /** 应用 spec：位置/尺寸/显隐（spec 变化后由 host 调用） */
    fun applySpec() {
        // content 铺满 widget（各布局根节点的固定高/weight 在自由尺寸下无意义，统一覆盖）
        content.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        val lp = (layoutParams as? LayoutParams) ?: LayoutParams(spec.w, spec.h).also { layoutParams = it }
        lp.leftMargin = spec.x
        lp.topMargin = spec.y
        lp.width = spec.w
        lp.height = spec.h
        layoutParams = lp
        // 设计模式：隐藏的 widget 半透明显示（仍可选中恢复）；运行模式直接 GONE
        if (spec.visible) {
            visibility = View.VISIBLE
            alpha = 1f
        } else if (designMode) {
            visibility = View.VISIBLE
            alpha = 0.35f
        } else {
            visibility = View.GONE
        }
        onSpecApplied()
    }

    /** spec 应用后回调（子类按需重设内部布局） */
    protected open fun onSpecApplied() {}

    // ── 生命周期（默认空实现） ─────────────────────

    open fun start() {}
    open fun stop() {}
    open fun destroy() {}
    open fun onThemeChange() {}

    /** VDWidget：窗口焦点就绪后拉起绑定应用（其他类型无操作） */
    open fun ensureLaunched() {}

    // 设计模式时容器必须消费 DOWN，设计器拖动依赖 onTouchListener 收到事件
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (designMode) return true
        return super.onTouchEvent(event)
    }

    /** 设计模式：拦截全部子 View 触摸（按钮/列表/滑条等不再响应点击），
     *  事件转交容器自身（其 OnTouchListener = 设计器拖动/选中）。 */
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = designMode
}
