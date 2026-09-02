package com.android.launcher37

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 高度按内容自适应（wrap_content）的 RecyclerView：
 * 覆写 [onMeasure] 强制测量全部子项，避免嵌套在 ScrollView 内时只测量到可视高度、
 * 超出部分被裁剪（默认 wrap_content 在滚动父容器下会被视口高度限制）。
 * 用于设置页"应用"排序列表，整页滚动交给外层 ScrollView。
 */
class WrapRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // 高度改为 AT_MOST 极大值 → 强制测量全部 item 的高度
        val expandSpec = MeasureSpec.makeMeasureSpec(
            Int.MAX_VALUE shr 2,
            MeasureSpec.AT_MOST
        )
        super.onMeasure(widthSpec, expandSpec)
    }
}
