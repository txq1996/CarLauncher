package com.android.launcher37

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [Store.pkgOf] 单元测试。
 *
 * 覆盖：
 * - 无 `/` 时原样返回
 * - 有 `/` 时只取前半段
 * - 异常输入（`/` 开头、空串）的容错
 */
class StorePkgOfTest {

    @Test
    fun pkgOf_noSlash_returnsInput() {
        assertEquals("com.example", Store.pkgOf("com.example"))
        assertEquals("", Store.pkgOf(""))
    }

    @Test
    fun pkgOf_withSlash_returnsPackagePart() {
        assertEquals("com.example", Store.pkgOf("com.example/.MainActivity"))
        assertEquals("com.example", Store.pkgOf("com.example/com.example.MainActivity"))
    }

    @Test
    fun pkgOf_slashAtStart_returnsInput() {
        // indexOf('/') = 0，不满足 > 0，原样返回
        assertEquals("/MainActivity", Store.pkgOf("/MainActivity"))
    }

    @Test
    fun pkgOf_emptyString_safe() {
        val result = Store.pkgOf("")
        assertNotEquals(null, result)
        assertEquals("", result)
    }
}
