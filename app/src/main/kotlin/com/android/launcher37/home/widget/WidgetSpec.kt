package com.android.launcher37.home.widget

/**
 * 主页 Widget 数据模型。
 *
 * 主页 = 自由画布：每个 Widget 绝对定位（x/y/w/h，单位 px，坐标系 = 屏幕像素），
 * 允许同一类型重复添加（如多个 VDWidget）。业务属性（字号/格式等）仍走
 * SettingsActivity SP（设置页统一调整）；布局属性由布局 JSON 持久化。
 *
 * @param id      实例唯一 id（递增持久化；VDWidget 槽位 = id + 1，重启保持稳定）
 * @param type    类型常量（[WidgetTypes]）
 * @param x/y/w/h 位置尺寸（px）；最小 20×20，不超出屏幕
 * @param visible 显隐（设计器控制；false 时不渲染）
 * @param config  实例自身配置（key-value 字符串；VDWidget 存 "pkg"）
 */
data class WidgetSpec(
    val id: Int,
    val type: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val visible: Boolean = true,
    val config: Map<String, String> = emptyMap()
)

/** Widget 类型常量 */
object WidgetTypes {
    const val TIME = "time"
    const val LYRICS = "lyrics"
    const val SPEED = "speed"
    const val VD = "vd"
    const val APPLIST = "applist"

    /** 设计器"添加"列表（显示名 + 类型） */
    val CATALOG: List<Pair<String, String>> = listOf(
        "时间" to TIME,
        "音乐" to LYRICS,
        "车速/导航" to SPEED,
        "VD 应用窗口" to VD,
        "应用列表" to APPLIST
    )
}

/** VDWidget 实例配置 key：绑定的应用包名 */
const val CFG_VD_PKG = "pkg"

/** 完整布局（version + 保存时屏幕尺寸 + widget 列表；数组顺序 = 绘制层级） */
data class HomeLayout(
    val version: Int = 1,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val widgets: List<WidgetSpec> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * 命名布局 = 名称 + 多页（至少一页，每页一个 [HomeLayout]）。
 * 内置模板（[LayoutRepository.BUILTIN_NAME]）为代码常量、只读，不入库。
 */
data class NamedLayout(
    val name: String,
    val pages: List<HomeLayout>
)
