package com.android.launcher37.home.widget

/**
 * Widget 可编辑属性描述（设计器"属性"面板渲染用）。
 *
 * 每个 Widget 实例通过自己的 [WidgetSpec.config]（key→value 字符串）独立保存属性，
 * 同一类型多实例互不影响。属性改动由 [WidgetHost.updateConfig] 写入 config 并回调
 * [WidgetView.onPropChanged] 实时应用。
 *
 * @param key       config 键
 * @param label     面板显示名
 * @param type      控件类型（决定面板渲染哪种控件）
 * @param default   缺省值字符串
 * @param min/max/step  INT 滑条范围
 * @param choices    CHOICE 候选项 / ORDER 有序候选项（显示名 to 值）
 */
enum class PropType { INT, BOOL, CHOICE, STRING, ORDER }

class WidgetProp(
    val key: String,
    val label: String,
    val type: PropType,
    val default: String,
    val min: Int = 0,
    val max: Int = 0,
    val step: Int = 1,
    val choices: List<Pair<String, String>> = emptyList()
)
