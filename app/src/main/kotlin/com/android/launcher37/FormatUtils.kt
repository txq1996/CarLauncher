package com.android.launcher37

/**
 * 通用字符串/距离/时长格式化工具。
 *
 * 全部方法为 `static`（Kotlin `object`），禁止实例化。距离/时长的空值约定：
 * `0` 或负数统一返回空串（除 [formatMs] 始终返回 `"00:00"` 外），
 * 与 UI 控件无文本时一致，避免显示 `"0m"` / `"0分"`。
 *
 * 设计原则：
 * - 无副作用：纯函数，不持有状态、不做 IO
 * - Locale 无关：使用 `String.format` 的默认 Locale（与现有 UI 行为保持一致；
 *   如需本地化小数点须显式指定 `Locale.US`）
 * - 不抛异常：所有非法输入收敛为空串或 `"00:00"`
 */
object FormatUtils {

    /** 距离小于等于该阈值时显示 `Nm`，否则以公里为单位 */
    private const val DISTANCE_M_THRESHOLD = 1500

    /**
     * 短距离/导航段距离格式化。
     *
     * 规则：
     * - `meter <= 0` → 空串
     * - `meter <= 1500` → `"<n>m"`
     * - `meter > 1500` → `"x.xkm"`（保留 1 位小数）
     *
     * @param meter 距离（米）
     * @return 形如 `"850m"` / `"12.5km"` 的字符串；非法输入返回 `""`
     */
    @JvmStatic
    fun formatDistance(meter: Int): String {
        if (meter <= 0) return ""
        if (meter > DISTANCE_M_THRESHOLD) return String.format("%.1fkm", meter / 1000f)
        return "${meter}m"
    }

    /**
     * 全程剩余距离格式化（与 [formatDistance] 不同之处：
     * 短距离也按 km 显示，避免出现 `"800m"` 这种实际应是"全程剩余"的视觉不一致）。
     *
     * @param meter 距离（米）
     * @return 形如 `"12.5km"` 的字符串；非法输入返回 `""`
     */
    @JvmStatic
    fun formatRemainDistance(meter: Int): String {
        if (meter <= 0) return ""
        return String.format("%.1fkm", meter / 1000f)
    }

    /**
     * 导航剩余时长格式化。
     *
     * 规则：
     * - `seconds <= 0` → 空串
     * - `hour > 0` → `"H:MM"`（24h+ 不折叠为天，遵循仪表盘常见写法）
     * - `hour == 0` → `"M分"`（中文分）
     *
     * @param seconds 时长（秒）
     * @return 形如 `"1:23"` / `"45分"` 的字符串
     */
    @JvmStatic
    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val hour = seconds / 3600
        val min = seconds / 60 % 60
        return if (hour > 0) "$hour:" + String.format("%02d", min) else "${min}分"
    }

    /**
     * 音乐进度时间（毫秒）格式化为 `"MM:SS"`。
     *
     * 与 [formatDuration] 不同：始终返回固定格式，即使输入为 0 或负数
     * 也返回 `"00:00"`，供音乐卡片当前/总时长两个 TextView 始终占位。
     *
     * @param ms 毫秒数
     * @return 形如 `"03:25"` / `"00:00"` 的字符串
     */
    @JvmStatic
    fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}
