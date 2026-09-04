package com.android.launcher37.util
import com.android.launcher37.SettingsActivity
import com.android.launcher37.R
import com.android.launcher37.util.Prefs
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
 * - [showWithWidth] 居中显示 PopupWindow，背景为 [R.drawable.bg_popup_panel]
 * - [titledPanel] 构造"标题 + 内容"的纵向 LinearLayout（标题用 `Theme.Holo` 大字体）
 *
 * 所有弹窗（应用选择器、按钮菜单、应用抽屉）都通过本类统一弹出，避免各处重复代码。
 */
object HoloPopup {

    /** 弹窗默认宽度（px） */
    const val WIDTH_SMALL = 300

    /**
     * 跟随 Activity 日/夜模式返回主题化 Context，使用本项目 `Theme.Launcher37` 以复用 `colors.xml` 配色。
     */
    @JvmStatic
    fun themedContext(a: Activity): Context {
        return ContextThemeWrapper(a, com.android.launcher37.R.style.Theme_Launcher37)
    }

    @JvmStatic
    fun showWithWidth(a: Activity, content: View, width: Int): PopupWindow {
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(content.context.getDrawable(R.drawable.bg_popup_panel))
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
        val titleSize = try { Prefs.of(themed).getInt(SettingsActivity.KEY_TS_TIME, 28) } catch (_: Exception) { 28 }
        val tv = TextView(themed, null, android.R.attr.textAppearanceLarge).apply {
            text = title
            // 标题为主文字：显式 foreground（textAppearanceLarge 走 framework 默认色，与 token 色值有偏差）
            setTextColor(androidx.core.content.ContextCompat.getColor(themed, R.color.foreground))
            setPadding(24, 18, 24, 6)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
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
