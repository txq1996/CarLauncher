package com.android.launcher37

/**
 * 导航/巡航行顺序的过滤与映射。
 *
 * 桌面设置持久化的统一键集（"navi_turn" / "cruise_road" 等）→ 渲染层短键
 * （"turn" / "road" / "dest" / "eta" / "alert"）。
 *
 * 短键仅 5 种：`turn` / `road` / `dest` / `eta` / `alert`。
 */
internal object NaviOrder {

    private val CRUISE_KEYS = setOf("cruise_road", "cruise_alert")
    private val NAVI_KEYS = setOf("navi_turn", "navi_road", "navi_dest", "navi_eta", "navi_alert")
    private val SHORT_BY_LONG = mapOf(
        "navi_turn" to "turn", "navi_road" to "road", "navi_dest" to "dest",
        "navi_eta" to "eta", "navi_alert" to "alert",
        "cruise_road" to "road", "cruise_alert" to "alert"
    )

    /**
     * 把统一键集（如 "navi_turn,navi_road,..."）过滤并映射为短键。
     *
     * @param unified 逗号分隔的统一键集
     * @param cruise  true → 仅保留 cruise_*；false → 仅保留 navi_*
     * @return 短键集（逗号分隔），空输入返回空串
     */
    @JvmStatic
    fun filter(unified: String?, cruise: Boolean): String {
        if (unified.isNullOrEmpty()) return ""
        val allowed = if (cruise) CRUISE_KEYS else NAVI_KEYS
        val sb = StringBuilder()
        for (k in unified.split(",")) {
            val t = k.trim()
            val short = SHORT_BY_LONG[t] ?: continue
            if (t in allowed) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(short)
            }
        }
        return sb.toString()
    }
}
