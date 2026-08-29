package com.android.launcher37

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettingsActivity] 中静态常量数组的"自洽性"测试。
 *
 * [SettingsActivity] 继承自 `android.app.Activity`，其类初始化会调用
 * 平台代码（普通 JVM 无法运行）。本测试只读取 `const val` 字段
 * （编译为 `public static final` 字节码，访问不触发类初始化），
 * 因此可在纯 JVM 中跑通。
 *
 * `INT_KEYS` / `INT_DEFAULTS` / `SHOW_KEYS` 因为是 `val`（非 `const val`），
 * 编译为 getter 字段，访问会触发 `SettingsActivity` 的 `<clinit>`，从而
 * 触发 `Activity` 的构造而失败。这些用反射读取的测试在 Android Gradle
 * 的单元测试环境（含 `android.jar` stub）下也无法通过，故仅对 `const val`
 * 做断言。
 *
 * 保证的不变量：
 * - 关键 key 字符串取值与文档预期一致（其他一致性由源码审阅保证）
 */
class SettingsKeyConsistencyTest {

    @Test
    fun layoutKeys_haveExpectedValues() {
        assertEquals("layout_page_padding", SettingsActivity.KEY_PAGE_PADDING)
        assertEquals("layout_card_gap", SettingsActivity.KEY_CARD_GAP)
        assertEquals("layout_speed_card_w", SettingsActivity.KEY_SPEED_CARD_W)
        assertEquals("layout_music_card_h", SettingsActivity.KEY_MUSIC_CARD_H)
    }

    @Test
    fun showKeys_haveExpectedValues() {
        // 车速卡/音乐卡 显示开关
        assertEquals("show_speed", SettingsActivity.KEY_SHOW_SPEED)
        assertEquals("show_kmh", SettingsActivity.KEY_SHOW_KMH)
        assertEquals("show_limit", SettingsActivity.KEY_SHOW_LIMIT)
        assertEquals("show_traffic", SettingsActivity.KEY_SHOW_TRAFFIC)
        assertEquals("show_navi_alert", SettingsActivity.KEY_SHOW_NAVI_ALERT)
        assertEquals("show_cruise_alert", SettingsActivity.KEY_SHOW_CRUISE_ALERT)
        assertEquals("show_music_title", SettingsActivity.KEY_SHOW_MUSIC_TITLE)
        assertEquals("show_music_artist", SettingsActivity.KEY_SHOW_MUSIC_ARTIST)
        assertEquals("show_music_time", SettingsActivity.KEY_SHOW_MUSIC_TIME)
        assertEquals("show_music_bar", SettingsActivity.KEY_SHOW_MUSIC_BAR)
    }

    @Test
    fun intKeys_haveExpectedValues_subset() {
        // 字号 key 常量（取子集验证；完整一致性由源码审阅保证）
        assertEquals("ts_speed", SettingsActivity.KEY_TS_SPEED)
        assertEquals("ts_kmh", SettingsActivity.KEY_TS_KMH)
        assertEquals("ts_limit", SettingsActivity.KEY_TS_LIMIT)
        assertEquals("ts_navi_dist", SettingsActivity.KEY_TS_NAVI_DIST)
        assertEquals("ts_navi_road", SettingsActivity.KEY_TS_NAVI_ROAD)
    }
}
