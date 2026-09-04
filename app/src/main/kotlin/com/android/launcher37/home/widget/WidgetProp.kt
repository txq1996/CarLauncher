package com.android.launcher37.home.widget

/**
 * Widget 可编辑属性描述（设计器"属性"面板渲染用）。
 *
 * 每个 Widget 实例通过自己的 [WidgetSpec.config]（key→value 字符串）独立保存属性，
 * 同一类型多实例互不影响。属性改动由 [WidgetHost.updateConfig] 写入 config 并回调
 * [WidgetView.onPropChanged] 实时应用。
 *
 * @param key       config 键；SHOW_SIZE 时为显隐键（"1"/"0"，缺省开）
 * @param label     面板显示名
 * @param type      控件类型（决定面板渲染哪种控件）
 * @param default   缺省值字符串；SHOW_SIZE 时为配对字号的缺省 px 值
 * @param min/max/step  INT 滑条范围（SHOW_SIZE 复用为字号范围）
 * @param choices    CHOICE 候选项 / ORDER 有序候选项（显示名 to 值）
 * @param pairKey    仅 SHOW_SIZE：配对字号键（存 px 整数），面板渲染"开关+字号滑条"同行
 */
enum class PropType { INT, BOOL, CHOICE, STRING, ORDER, SHOW_SIZE }

class WidgetProp(
    val key: String,
    val label: String,
    val type: PropType,
    val default: String,
    val min: Int = 0,
    val max: Int = 0,
    val step: Int = 1,
    val choices: List<Pair<String, String>> = emptyList(),
    val pairKey: String = ""
)
