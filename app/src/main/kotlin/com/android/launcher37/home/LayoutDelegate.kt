package com.android.launcher37.home

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher37.IconCache
import com.android.launcher37.R
import com.android.launcher37.SettingsActivity

/**
 * 布局委派：视图绑定 / 主题 / 字号 / 间距快照 / 卡片配色。
 *
 * 持有 Activity 强引用（与原 god class 一致）；
 * 生命周期 = Activity，不允许跨 Activity 复用。
 */
class LayoutDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews
) {
    private val textSizers: Array<TextSizer> = arrayOf(
        TextSizer(views.tvMusicName, SettingsActivity.KEY_TS_MUSIC_TITLE, 24),
        TextSizer(views.tvArtist, SettingsActivity.KEY_TS_MUSIC_ARTIST, 15),
        TextSizer(views.curTime, SettingsActivity.KEY_TS_MUSIC_TIME, 15),
        TextSizer(views.totalTime, SettingsActivity.KEY_TS_MUSIC_TIME, 15)
    )

    fun apply(snapshot: SettingsSnapshot) {
        applyLayout(snapshot)
        applyTextSizes(snapshot)
        applyMusicVisibility(snapshot)
    }

    fun applyLayout(snapshot: SettingsSnapshot) {
        val pad = snapshot.size(SettingsActivity.KEY_PAGE_PADDING, 10)
        views.pageContent.setPadding(pad, pad, pad, pad)
        val gap = snapshot.size(SettingsActivity.KEY_CARD_GAP, 10)
        views.gapTimeSpeed.updateLp(matchH = gap)
        views.gapSpeedMusic.updateLp(matchH = gap)
        views.gapDock.updateLp(matchH = gap)
        views.gapCol.updateLp(matchW = gap)
        views.leftCol.layoutParams.width = snapshot.size(SettingsActivity.KEY_SPEED_CARD_W, 260)
        views.cardTime.layoutParams.height = snapshot.size(SettingsActivity.KEY_TIME_CARD_H, 60)
        views.cardMusic.layoutParams.height = snapshot.size(SettingsActivity.KEY_MUSIC_CARD_H, 180)
        val dockH = snapshot.size(SettingsActivity.KEY_DOCK_HEIGHT, 80)
        views.dockGrid.layoutParams.height = dockH
        // 卡片显隐
        val showLeft = snapshot.show(SettingsActivity.KEY_SHOW_LEFT_COLUMN, true)
        views.leftCol.visibility = if (showLeft) View.VISIBLE else View.GONE
        views.gapCol.visibility = if (showLeft) View.VISIBLE else View.GONE
        val showTime = snapshot.show(SettingsActivity.KEY_SHOW_TIME, false)
        val showSpeed = snapshot.show(SettingsActivity.KEY_SHOW_SPEED_CARD, true)
        val showMusic = snapshot.show(SettingsActivity.KEY_SHOW_MUSIC_CARD, true)
        val showDock = snapshot.show(SettingsActivity.KEY_SHOW_DOCK, true)
        views.cardTime.visibility = if (showTime) View.VISIBLE else View.GONE
        views.cardSpeed.visibility = if (showSpeed) View.VISIBLE else View.GONE
        views.cardMusic.visibility = if (showMusic) View.VISIBLE else View.GONE
        views.gapTimeSpeed.visibility = if (showTime && showSpeed) View.VISIBLE else View.GONE
        views.gapSpeedMusic.visibility = if (showSpeed && showMusic) View.VISIBLE else if (!showSpeed && showTime && showMusic) View.VISIBLE else View.GONE
        views.dockGrid.visibility = if (showDock) View.VISIBLE else View.GONE
        views.gapDock.visibility = if (showDock) View.VISIBLE else View.GONE
        views.tvTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, snapshot.size(SettingsActivity.KEY_TS_TIME, 28).toFloat())
    }

    fun applyTextSizes(snapshot: SettingsSnapshot) {
        for ((view, key, def) in textSizers) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, snapshot.size(key, def).toFloat())
        }
    }

    fun applyMusicVisibility(snapshot: SettingsSnapshot) {
        setVis(views.tvMusicName, SettingsActivity.KEY_SHOW_MUSIC_TITLE, snapshot)
        setVis(views.tvArtist, SettingsActivity.KEY_SHOW_MUSIC_ARTIST, snapshot)
        setVis(views.musicTimeRow, SettingsActivity.KEY_SHOW_MUSIC_TIME, snapshot)
        setVis(views.musicProgress, SettingsActivity.KEY_SHOW_MUSIC_BAR, snapshot)
    }

    private fun setVis(v: View, key: String, snapshot: SettingsSnapshot) {
        v.visibility = if (snapshot.show(key)) View.VISIBLE else View.GONE
    }

    /**
     * 日夜主题切换：根背景 + 两张卡片底色（重建 GradientDrawable 替代 @drawable/bg_card）
     * + 各静态文字颜色。红绿灯三色 / 超速色 / 导航提醒黄在各自渲染点直接读 R.color.*。
     */
    fun applyTheme() {
        val bg = activity.resources.getColor(R.color.background, activity.theme)
        // 状态栏跟随日/夜主题切换：configChanges=uiMode 不会重建 Activity，
        // 因此仅在 onCreate 设置一次会卡在初始配色，必须在 applyTheme 同步。
        activity.window.statusBarColor = bg
        // 同步状态栏前景（图标/文字）深浅：日间深色图标/夜间浅色图标
        val isLightBar = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_NO
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val ctrl = activity.window.insetsController
            if (isLightBar) ctrl?.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            else ctrl?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (isLightBar) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
        views.contentRoot.setBackgroundColor(bg)
        val card = activity.resources.getColor(R.color.surface, activity.theme)
        views.cardTime.background = GradientDrawable().apply { setColor(card) }
        views.cardSpeed.background = GradientDrawable().apply { setColor(card) }
        views.cardMusic.background = GradientDrawable().apply { setColor(card) }
        views.pipPlaceholder.background = GradientDrawable().apply {
            setColor(activity.resources.getColor(R.color.surface_variant, activity.theme))
            setStroke(2, activity.resources.getColor(R.color.divider))
        }

        val primary = activity.resources.getColor(R.color.foreground, activity.theme)
        val secondary = activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        val tint = ColorStateList.valueOf(primary)
        views.btnPrev.imageTintList = tint
        views.btnPlayPause.imageTintList = tint
        views.btnNext.imageTintList = tint
        applyTextColors(primary, secondary)
    }

    private fun applyTextColors(primary: Int, secondary: Int) {
        for (v in PRIMARY_TEXT_VIEWS) v.setTextColor(primary)
        for (v in SECONDARY_TEXT_VIEWS) v.setTextColor(secondary)
    }

    /**
     * 主题切换后逐个 view 强制重设日/夜 drawable（原 god class 散在
     * `onConfigurationChanged` 末尾的 4 行 setBackgroundResource）。
     */
    fun reapplyNightDrawables() {
        // 主题切换时归一化图标缓存会持有错误主题的 drawable，必须清空
        IconCache.clearNormalized()
        views.musicProgress.progressDrawable = activity.resources.getDrawable(R.drawable.progress_music)
        // 强制重绘 progressDrawable（自赋值是 no-op，invalidate 才能触发刷新）
        views.musicProgress.invalidate()
        views.pipPlaceholder.setBackgroundResource(R.drawable.bg_pip_frame)
        views.btnPrev.setBackgroundResource(R.drawable.bg_icon_btn)
        views.btnPlayPause.setBackgroundResource(R.drawable.bg_icon_btn)
        views.btnNext.setBackgroundResource(R.drawable.bg_icon_btn)
    }

    private val PRIMARY_TEXT_VIEWS get() = arrayOf(
        views.tvMusicName, views.tvNaviDist, views.tvNaviRoad
    )
    private val SECONDARY_TEXT_VIEWS get() = arrayOf(
        views.tvArtist, views.curTime, views.totalTime,
        views.tvNaviDest, views.tvNaviRemain, views.tvNaviTime, views.tvNaviAlert
    )

    private data class TextSizer(val view: TextView, val key: String, val default: Int)

    private fun View.updateLp(matchW: Int = 0, matchH: Int = 0) {
        layoutParams = LinearLayout.LayoutParams(
            if (matchW != 0) matchW else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (matchH != 0) matchH else ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
