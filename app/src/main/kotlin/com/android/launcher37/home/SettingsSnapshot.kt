package com.android.launcher37.home

import com.android.launcher37.Prefs
import com.android.launcher37.SettingsActivity

/**
 * 设置快照（onCreate 一次性读取，关闭设置后重启桌面生效）。
 *
 * 把原 [com.android.launcher37.LauncherActivity] 中两份 `HashMap<String, …>`
 * 抽成不可变快照；设置项的所有"开关/尺寸"通过这份快照读取，避免散落读写。
 */
class SettingsSnapshot private constructor(
    private val showPrefs: Map<String, Boolean>,
    private val intPrefs: Map<String, Int>
) {
    fun show(key: String, default: Boolean = true): Boolean =
        showPrefs[key] ?: default

    fun size(key: String, default: Int): Int =
        intPrefs[key] ?: default

    companion object {
        fun load(context: android.content.Context): SettingsSnapshot {
            val p = Prefs.of(context)
            val show = HashMap<String, Boolean>(SettingsActivity.SHOW_KEYS.size)
            for (key in SettingsActivity.SHOW_KEYS)
                show[key] = p.getBoolean(key, SettingsActivity.SHOW_DEFAULTS[key] ?: true)
            val intMap = HashMap<String, Int>(SettingsActivity.INT_KEYS.size)
            for (i in SettingsActivity.INT_KEYS.indices) {
                intMap[SettingsActivity.INT_KEYS[i]] =
                    p.getInt(SettingsActivity.INT_KEYS[i], SettingsActivity.INT_DEFAULTS[i])
            }
            return SettingsSnapshot(show, intMap)
        }
    }
}
