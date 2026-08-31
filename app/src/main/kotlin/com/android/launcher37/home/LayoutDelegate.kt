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
        TextSizer(views.tvSpeed, SettingsActivity.KEY_TS_SPEED, 110),
        TextSizer(views.tvKm, SettingsActivity.KEY_TS_KMH, 20),
        TextSizer(views.tvLimit, SettingsActivity.KEY_TS_LIMIT, 17),
        TextSizer(views.tvTrafficSec, SettingsActivity.KEY_TS_TRAFFIC_SEC, 36),
        // 顶部"车速/kmh/限速/红绿灯"是固定行；以下 navi/cruise 各 view 的字号
        // 由 NaviPanelDelegate 在 addView 时按当前模式 key 应用，故此处不再预设。
        TextSizer(views.tvMusicName, SettingsActivity.KEY_TS_MUSIC_TITLE, 24),
        TextSizer(views.tvArtist, SettingsActivity.KEY_TS_MUSIC_ARTIST, 15),
        TextSizer(views.curTime, SettingsActivity.KEY_TS_MUSIC_TIME, 15),
        TextSizer(views.totalTime, SettingsActivity.KEY_TS_MUSIC_TIME, 15)
    )

    fun apply(snapshot: SettingsSnapshot) {
        applyLayout(snapshot)
        applyTextSizes(snapshot)
        applySpeedVisibility(snapshot)
        applyMusicVisibility(snapshot)
    }

    fun applyLayout(snapshot: SettingsSnapshot) {
        val pad = snapshot.size(SettingsActivity.KEY_PAGE_PADDING, 0)
        views.pageContent.setPadding(pad, pad, pad, pad)
        val gap = snapshot.size(SettingsActivity.KEY_CARD_GAP, 0)
        views.gapSpeedMusic.updateLp(matchH = gap)
        views.gapDock.updateLp(matchH = gap)
        views.gapCol.updateLp(matchW = gap)
        views.leftCol.layoutParams.width = snapshot.size(SettingsActivity.KEY_SPEED_CARD_W, 260)
        views.cardMusic.layoutParams.height = snapshot.size(SettingsActivity.KEY_MUSIC_CARD_H, 200)
        views.dockGrid.layoutParams.height = DOCK_GRID_HEIGHT_PX
    }

    fun applyTextSizes(snapshot: SettingsSnapshot) {
        for ((view, key, def) in textSizers) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, snapshot.size(key, def).toFloat())
        }
    }

    fun applySpeedVisibility(snapshot: SettingsSnapshot) {
        setVis(views.tvSpeed, SettingsActivity.KEY_SHOW_SPEED, snapshot)
        setVis(views.tvKm, SettingsActivity.KEY_SHOW_KMH, snapshot)
        setVis(views.tvLimit, SettingsActivity.KEY_SHOW_LIMIT, snapshot)
        setVis(views.tvTraffic, SettingsActivity.KEY_SHOW_TRAFFIC, snapshot)
        setVis(views.tvTrafficSec, SettingsActivity.KEY_SHOW_TRAFFIC, snapshot)
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
        views.contentRoot.setBackgroundColor(activity.resources.getColor(R.color.background, activity.theme))
        val card = activity.resources.getColor(R.color.surface, activity.theme)
        views.cardSpeed.background = GradientDrawable().apply { setColor(card) }
        views.cardMusic.background = GradientDrawable().apply { setColor(card) }
        views.pipPlaceholder.background = GradientDrawable().apply {
            setColor(activity.resources.getColor(R.color.scrim, activity.theme))
            setStroke(2, activity.resources.getColor(R.color.outlineVariant))
        }

        val primary = activity.resources.getColor(R.color.onSurface, activity.theme)
        val secondary = activity.resources.getColor(R.color.onSurfaceVariant, activity.theme)
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
        views.tvSpeed, views.tvMusicName, views.tvNaviDist, views.tvNaviRoad
    )
    private val SECONDARY_TEXT_VIEWS get() = arrayOf(
        views.tvKm, views.tvLimit, views.tvArtist, views.curTime, views.totalTime,
        views.tvNaviDest, views.tvNaviRemain, views.tvNaviTime, views.tvNaviAlert
    )

    private data class TextSizer(val view: TextView, val key: String, val default: Int)

    private fun View.updateLp(matchW: Int = 0, matchH: Int = 0) {
        layoutParams = LinearLayout.LayoutParams(
            if (matchW != 0) matchW else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (matchH != 0) matchH else ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        private const val DOCK_GRID_HEIGHT_PX = 80
    }
}
