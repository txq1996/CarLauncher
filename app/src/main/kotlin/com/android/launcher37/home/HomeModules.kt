package com.android.launcher37.home

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.android.launcher37.R

/**
 * 桌面卡片模块注册表：左/右栏均为"模块容器"，卡片以 (模块id → 布局资源) 注册，
 * 按序 inflate + 模块间插入间距（Space），为后续"用户自定义布局"铺路。
 *
 * 当前默认布局 = LEFT_DEFAULT / RIGHT_DEFAULT（后续从设置读取用户排列）。
 * 模块自身逻辑在各 Delegate（TimeDelegate/SpeedDelegate/MusicDelegate/LyricsDelegate），
 * view 引用仍集中装配在 [HomeViews]。
 */
object HomeModules {
    const val TIME = 0
    const val SPEED = 1
    const val MUSIC = 2
    const val LYRICS = 3

    private val LAYOUTS = mapOf(
        TIME to R.layout.card_time,
        SPEED to R.layout.card_speed,
        MUSIC to R.layout.card_music,
        LYRICS to R.layout.card_lyrics
    )

    /** 默认布局：左栏（时间/车速/音乐），右栏（歌词） */
    val LEFT_DEFAULT = listOf(TIME, SPEED, MUSIC)
    val RIGHT_DEFAULT = listOf(LYRICS)

    /**
     * 向容器按序填充模块，模块间插入高度 gapPx 的 Space。
     *
     * @return gaps[i] = 第 i 与第 i+1 个模块之间的间距 view（不足两模块时列表为空）。
     *   调用方按当前默认布局顺序映射到 HomeViews 的 gap 字段（gapTimeSpeed/gapSpeedMusic）；
     *   未来自定义布局会改为按模块 id 取间距，届时 HomeViews 的 gap 字段一并重构。
     */
    fun fill(activity: Activity, container: LinearLayout, modules: List<Int>, gapPx: Int): List<View> {
        container.removeAllViews()
        val gaps = ArrayList<View>(modules.size.coerceAtLeast(1) - 1)
        for ((index, id) in modules.withIndex()) {
            if (index > 0) {
                val gap = View(activity)
                container.addView(gap, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, gapPx
                ))
                gaps.add(gap)
            }
            val res = LAYOUTS[id] ?: continue
            activity.layoutInflater.inflate(res, container, true)
        }
        return gaps
    }

    /** 模块间占位（该位置无间距时的空 view，保持 HomeViews 字段非空） */
    fun blankGap(activity: Activity): View {
        val v = View(activity)
        v.visibility = View.GONE
        return v
    }
}
