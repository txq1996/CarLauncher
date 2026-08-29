package com.android.launcher37

import com.android.launcher37.Store.V2Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Store.V2Button] 单元测试。
 *
 * 覆盖：
 * - 5 个工厂方法的字段填充
 * - [V2Button.sameAs] 全字段判等（含分屏左右顺序）
 * - 不可变性（final 字段 / data class）
 * - 不同工厂方法产生的对象互不相等（即使未填写字段被空串占位）
 */
class StoreV2ButtonTest {

    @Test
    fun map_setsTypeAndActionOnly() {
        val b = V2Button.map("home")
        assertEquals("map", b.type)
        assertEquals("home", b.action)
        assertEquals("", b.id)
        assertEquals("", b.left)
        assertEquals("", b.right)
    }

    @Test
    fun app_setsTypeAndIdOnly() {
        val b = V2Button.app("com.example/.MainActivity")
        assertEquals("app", b.type)
        assertEquals("", b.action)
        assertEquals("com.example/.MainActivity", b.id)
        assertEquals("", b.left)
        assertEquals("", b.right)
    }

    @Test
    fun split_setsTypeLeftRightOnly() {
        val b = V2Button.split("com.x/.L", "com.y/.R")
        assertEquals("split", b.type)
        assertEquals("com.x/.L", b.left)
        assertEquals("com.y/.R", b.right)
    }

    @Test
    fun clean_hasNoPayload() {
        val b = V2Button.clean()
        assertEquals("clean", b.type)
        assertEquals("", b.action)
        assertEquals("", b.id)
    }

    @Test
    fun settings_hasNoPayload() {
        val b = V2Button.settings()
        assertEquals("settings", b.type)
        assertEquals("", b.action)
    }

    @Test
    fun sameAs_identicalContent_isTrue() {
        assertTrue(V2Button.map("home").sameAs(V2Button.map("home")))
        assertTrue(V2Button.app("a/b").sameAs(V2Button.app("a/b")))
        assertTrue(V2Button.clean().sameAs(V2Button.clean()))
    }

    @Test
    fun sameAs_differentType_isFalse() {
        assertFalse(V2Button.map("home").sameAs(V2Button.clean()))
        assertFalse(V2Button.clean().sameAs(V2Button.settings()))
    }

    @Test
    fun sameAs_differentAction_isFalse() {
        assertFalse(V2Button.map("home").sameAs(V2Button.map("company")))
        assertFalse(V2Button.map("home").sameAs(V2Button.map("stop")))
    }

    @Test
    fun sameAs_differentId_isFalse() {
        assertFalse(V2Button.app("a/b").sameAs(V2Button.app("a/c")))
    }

    @Test
    fun sameAs_splitOrderMatters() {
        // 分屏左右顺序不同即视为不同（用户意图不同）
        val a = V2Button.split("com.x/.L", "com.y/.R")
        val b = V2Button.split("com.y/.R", "com.x/.L")
        assertFalse(a.sameAs(b))
    }

    @Test
    fun factories_returnDistinctInstances() {
        // 每次调用都是新对象（不可变但非单例）
        val a = V2Button.clean()
        val b = V2Button.clean()
        assertNotSame(a as Any, b as Any)
        // 同内容 sameAs 仍为 true
        assertTrue(a.sameAs(b))
    }

    @Test
    fun map_homeCompanyStopAreDistinct() {
        val home = V2Button.map("home")
        val company = V2Button.map("company")
        val stop = V2Button.map("stop")
        // 类型相同但 action 不同
        assertFalse(home.sameAs(company))
        assertFalse(home.sameAs(stop))
        assertFalse(company.sameAs(stop))
    }
}
