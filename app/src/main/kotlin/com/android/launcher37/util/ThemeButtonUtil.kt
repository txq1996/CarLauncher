package com.android.launcher37.util

import android.content.Context
import android.widget.Button
import com.android.launcher37.R

/**
 * 通用按钮工厂：复用 ThemeButton 样式（styles.xml）。
 *
 * XML 按钮直接用 `style="@style/ThemeButton"`；动态创建的按钮必须手动设置属性，
 * 本函数作为唯一入口，保证 [SettingsActivity.actionButton][com.android.launcher37.SettingsActivity]、
 * [SettingsActivity.dialogButton][com.android.launcher37.SettingsActivity]、
 * [UpdateDelegate.label][com.android.launcher37.home.UpdateDelegate]
 * 三处按钮视觉一致。
 */
object ThemeButtonUtil {

    /**
     * 对已创建的 [Button] 应用 ThemeButton 样式属性。
     * 背景 bg_btn / 文字色 foreground 走 colors.xml（日夜自动切换）。
     */
    @JvmStatic
    fun apply(button: Button) {
        button.apply {
            setBackgroundResource(R.drawable.bg_btn)
            setTextColor(context.getColor(R.color.foreground))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f)
            stateListAnimator = null
            minHeight = 56
            minimumHeight = 0
            minimumWidth = 0
            setPadding(20, 0, 20, 0)
        }
    }
}
