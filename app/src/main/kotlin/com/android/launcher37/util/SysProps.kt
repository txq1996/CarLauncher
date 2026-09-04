package com.android.launcher37.util
import java.lang.reflect.Method

/**
 * `android.os.SystemProperties` 的反射封装。
 *
 * `SystemProperties` 是隐藏 API（`@hide`），未在公开 SDK 中导出，
 * 但 FYT 平台（车机）大量系统属性读取需要走它（例如英里制开关 `persist.sys.isMiles`）。
 *
 * 类初始化时一次性解析 `get(String, String)` 静态方法并缓存。
 * 读取失败时静默返回 `def`，调用方无需 try/catch。
 */
object SysProps {

    private val sGet: Method? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    } catch (e: Exception) {
        // 反射失败（极少见，比如被 ProGuard 改名）→ 静默退化，sGet 留 null
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
}
