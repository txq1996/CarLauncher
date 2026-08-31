package com.android.launcher37

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Holo 风格 PopupWindow 统一封装。
 *
 * - [themedContext] 根据 Activity 当前 uiMode 返回日/夜主题的 [ContextThemeWrapper]
 * - [show] 居中显示 PopupWindow，背景为 [R.drawable.bg_holo_dialog]
 * - [titledPanel] 构造"标题 + 内容"的纵向 LinearLayout（标题用 `Theme.Holo` 大字体）
 *
 * 所有弹窗（应用选择器、按钮菜单、应用抽屉）都通过本类统一弹出，避免各处重复代码。
 */
object HoloPopup {

    /** 弹窗默认宽度（px） */
    const val WIDTH = 400

    /**
     * 跟随 Activity 日/夜模式返回主题化 Context。
     *
     * 日间：`Theme.Holo.Light`；夜间：`Theme.Holo`。
     * 弹窗内控件继承该 Context 的主题。
     */
    @JvmStatic
    fun themedContext(a: Activity): Context {
        val mask = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val theme = if (mask == Configuration.UI_MODE_NIGHT_YES)
            android.R.style.Theme_Holo
        else
            android.R.style.Theme_Holo_Light
        return ContextThemeWrapper(a, theme)
    }

    /**
     * 居中显示 PopupWindow。
     *
     * 背景：@drawable/bg_holo_dialog；可被外部 touch 关闭。
     *
     * @return 弹窗实例（调用方在 dismiss 后无需再释放，PopupWindow 自管）
     */
    @JvmStatic
    fun show(a: Activity, content: View): PopupWindow {
        val popup = PopupWindow(content, WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(content.context.getDrawable(R.drawable.bg_holo_dialog))
        popup.isOutsideTouchable = true
        popup.showAtLocation(a.window.decorView, Gravity.CENTER, 0, 0)
        return popup
    }

    /**
     * 构造"标题 + 内容"纵向 LinearLayout（用于 ListView 弹窗的标准外壳）。
     */
    @JvmStatic
    fun titledPanel(themed: Context, title: CharSequence, content: View): View {
        val panel = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tv = TextView(themed, null, android.R.attr.textAppearanceLarge).apply {
            text = title
            setPadding(24, 18, 24, 6)
        }
        panel.addView(
            tv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return panel
    }
}
