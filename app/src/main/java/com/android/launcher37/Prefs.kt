package com.android.launcher37

import android.content.Context
import android.content.SharedPreferences

/**
 * 桌面设置（`SharedPreferences`）统一入口。
 *
 * 所有设置项统一存在 [FILE]（`"launcher37_config"`）下，
 * 通过 `SettingsActivity` 即时写入，`LauncherActivity.loadSettings`
 * 在 `onCreate` 一次性快照读出。修改设置后需重启桌面生效。
 */
object Prefs {

    /** 桌面设置 SP 文件名，所有设置项的容器 */
    const val FILE = "launcher37_config"

    /**
     * 取桌面设置 SharedPreferences 实例。
     *
     * @param c 任意 Context（推荐使用 `Application` 上下文以避免 Activity 泄漏）
     * @return 私有模式的 SP 实例
     */
    @JvmStatic
    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 字符串设置项便捷读取。
     *
     * @param c   任意 Context
     * @param key 设置项 key
     * @param def 未设置时的默认值
     * @return 当前值或默认值
     */
    @JvmStatic
    fun getString(c: Context, key: String, def: String?): String? =
        of(c).getString(key, def)
}
