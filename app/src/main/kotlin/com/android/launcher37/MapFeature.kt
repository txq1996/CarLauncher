package com.android.launcher37

/**
 * 底栏/弹窗 6 个快捷功能的 emoji + action 名称常量表。
 *
 * 集中维护避免 [DockBar] / [AppDrawer] / [MapActions] 三处硬编码不一致
 * （如之前 emoji unicode escape 在 DockBar + AppDrawer 重复 9 次）。
 */
internal object MapFeature {

    /** map action -> emoji */
    val HOME_EMOJI = "\uD83C\uDFE1"       // house
    val COMPANY_EMOJI = "\uD83D\uDCBC"     // briefcase
    val STOP_EMOJI = "\uD83D\uDED1"        // stop sign

    /** standalone (no map action) */
    val CLEAN_EMOJI = "\uD83E\uDDF9"       // mop
    val SETTINGS_EMOJI = "\u2699\uFE0F"     // gear ⚙️
    val SPLIT_EMOJI = "\uD83E\uDDE9"       // puzzle 🧩
    val GOHOME_EMOJI = "\uD83E\uDDED"      // compass 🧭（返回主页）
}
