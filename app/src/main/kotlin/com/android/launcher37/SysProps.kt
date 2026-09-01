package com.android.launcher37

import java.lang.reflect.Method

/**
 * `android.os.SystemProperties` 的反射封装。
 *
 * `SystemProperties` 是隐藏 API（`@hide`），未在公开 SDK 中导出，
 * 但 FYT 平台（车机）大量系统属性读写需要走它（例如 PIP 矩形上报、launcher 特征等）。
 *
 * 类初始化时一次性解析 `get(String, String)` / `set(String, String)`
 * 两个静态方法并缓存。读取/写入失败时静默返回 `def` 或忽略，调用方无需 try/catch。
 *
 * **系统 uid 要求**：`set` 写入会受 SELinux 限制（普通应用被拒），
 * 本应用为 `sharedUserId=android.uid.system`，具备写权限。
 */
object SysProps {

    private val sGet: Method? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    } catch (e: Exception) {
        // 反射失败（极少见，比如被 ProGuard 改名）→ 静默退化，sGet/sSet 留 null
        null
    }

    private val sSet: Method? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("set", String::class.java, String::class.java)
    } catch (e: Exception) {
        null
    }

    /**
     * 读取系统属性。
     *
     * @param key 属性名
     * @param def 属性不存在或读取失败时返回的默认值
     * @return 属性值或 `def`
     */
    @JvmStatic
    fun get(key: String, def: String): String = try {
        (sGet?.invoke(null, key, def) as? String) ?: def
    } catch (e: Exception) {
        def
    }

    /**
     * 写入系统属性。
     *
     * 受 SELinux 与 system uid 双重限制；非系统应用调用会被静默拒绝。
     *
     * @param key   属性名
     * @param value 属性值
     */
    @JvmStatic
    fun set(key: String, value: String) {
        try {
            sSet?.invoke(null, key, value)
        } catch (e: Exception) {
            // 静默失败
        }
    }
}
