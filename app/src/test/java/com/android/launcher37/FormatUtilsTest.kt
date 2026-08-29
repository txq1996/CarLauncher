package com.android.launcher37

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FormatUtils] 单元测试。
 *
 * 覆盖范围：
 * - 距离/时长格式化边界条件（0、负数、阈值）
 * - [FormatUtils.formatDistance] 与 [FormatUtils.formatRemainDistance] 在短距离下行为差异
 * - [FormatUtils.formatMs] 永远返回有效格式（即使非法输入）
 * - [FormatUtils.formatDuration] 时分切换
 */
class FormatUtilsTest {

    // ── formatDistance ─────────────────────────────────

    @Test
    fun formatDistance_zeroOrNegative_returnsEmpty() {
        assertEquals("", FormatUtils.formatDistance(0))
        assertEquals("", FormatUtils.formatDistance(-1))
        assertEquals("", FormatUtils.formatDistance(Int.MIN_VALUE))
    }

    @Test
    fun formatDistance_atOrBelowThreshold_usesMeter() {
        assertEquals("1m", FormatUtils.formatDistance(1))
        assertEquals("850m", FormatUtils.formatDistance(850))
        assertEquals("1500m", FormatUtils.formatDistance(1500))
    }

    @Test
    fun formatDistance_aboveThreshold_usesKilometer() {
        assertEquals("1.5km", FormatUtils.formatDistance(1501))
        assertEquals("12.5km", FormatUtils.formatDistance(12500))
    }

    // ── formatRemainDistance ───────────────────────────

    @Test
    fun formatRemainDistance_zeroOrNegative_returnsEmpty() {
        assertEquals("", FormatUtils.formatRemainDistance(0))
        assertEquals("", FormatUtils.formatRemainDistance(-1))
    }

    @Test
    fun formatRemainDistance_usesKilometerEvenForShort() {
        // 全程剩余距离：500m 也按 km 显示，避免视觉不一致
        assertEquals("0.5km", FormatUtils.formatRemainDistance(500))
        assertEquals("1.5km", FormatUtils.formatRemainDistance(1500))
        assertEquals("12.5km", FormatUtils.formatRemainDistance(12500))
    }

    // ── formatDuration ────────────────────────────────

    @Test
    fun formatDuration_zeroOrNegative_returnsEmpty() {
        assertEquals("", FormatUtils.formatDuration(0))
        assertEquals("", FormatUtils.formatDuration(-1))
    }

    @Test
    fun formatDuration_lessThanOneHour_usesChineseMinutes() {
        assertEquals("0分", FormatUtils.formatDuration(1))
        assertEquals("45分", FormatUtils.formatDuration(45 * 60))
        assertEquals("59分", FormatUtils.formatDuration(59 * 60 + 59))
    }

    @Test
    fun formatDuration_atLeastOneHour_usesHColonMM() {
        assertEquals("1:00", FormatUtils.formatDuration(3600))
        assertEquals("1:23", FormatUtils.formatDuration(3600 + 23 * 60))
        assertEquals("2:05", FormatUtils.formatDuration(2 * 3600 + 5 * 60))
    }

    // ── formatMs ───────────────────────────────────────

    @Test
    fun formatMs_zeroOrNegative_returnsZero() {
        // 与其它 formatter 不同：始终返回有效格式
        assertEquals("00:00", FormatUtils.formatMs(0))
        assertEquals("00:00", FormatUtils.formatMs(-1))
    }

    @Test
    fun formatMs_typical() {
        assertEquals("00:01", FormatUtils.formatMs(1000))
        assertEquals("03:25", FormatUtils.formatMs(3 * 60_000L + 25_000L))
        assertEquals("59:59", FormatUtils.formatMs(60 * 60_000L - 1000L))
    }
}
