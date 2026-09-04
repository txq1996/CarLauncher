package com.android.launcher37.navi
import com.android.launcher37.navi.MapActions
import com.android.launcher37.drawer.AppDrawer
/**
 * 抽屉/弹窗快捷功能项的 emoji 常量表（2 个 map action + 5 个独立功能）。
 *
 * 集中维护避免 [AppDrawer] / [DrawerAdapter] / [MapActions] 多处硬编码不一致
 * （如 emoji unicode escape 在多处重复）。
 */
internal object MapFeature {

    /** map action -> emoji */
    val HOME_EMOJI = "\uD83C\uDFE1"       // house
    val COMPANY_EMOJI = "\uD83D\uDCBC"     // briefcase

    /** standalone (no map action) */
    val CLEAN_EMOJI = "\uD83E\uDDF9"       // mop
    val SETTINGS_EMOJI = "\u2699\uFE0F"     // gear ⚙️
    val SPLIT_EMOJI = "\uD83E\uDDE9"       // puzzle 🧩
    val GOHOME_EMOJI = "\uD83E\uDDED"      // compass 🧭（返回主页）
    val RESTART_EMOJI = "\uD83D\uDD04"     // arrows 🔄（重启桌面）
}
