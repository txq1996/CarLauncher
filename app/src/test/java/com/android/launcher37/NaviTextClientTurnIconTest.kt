package com.android.launcher37

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NaviTextClient.turnIconRes] / [NaviTextClient.turnIconMirrored] /
 * [NaviTextClient.cameraTypeName] 单元测试。
 *
 * 这些是纯静态方法，调用方期望精确的 ICON→图标资源映射与镜像标记；
 * 一旦映射错位会导致导航面板图标显示错误。
 */
class NaviTextClientTurnIconTest {

    @Test
    fun turnIconRes_left() {
        assertEquals(R.drawable.ic_navi_left, NaviTextClient.turnIconRes(2))
    }

    @Test
    fun turnIconRes_right() {
        assertEquals(R.drawable.ic_navi_right, NaviTextClient.turnIconRes(3))
    }

    @Test
    fun turnIconRes_leftFront() {
        assertEquals(R.drawable.ic_navi_left_front, NaviTextClient.turnIconRes(4))
    }

    @Test
    fun turnIconRes_rightFront() {
        assertEquals(R.drawable.ic_navi_right_front, NaviTextClient.turnIconRes(5))
    }

    @Test
    fun turnIconRes_leftBack() {
        assertEquals(R.drawable.ic_navi_left_back, NaviTextClient.turnIconRes(6))
    }

    @Test
    fun turnIconRes_rightBack() {
        assertEquals(R.drawable.ic_navi_right_back, NaviTextClient.turnIconRes(7))
    }

    @Test
    fun turnIconRes_uturnLeft() {
        assertEquals(R.drawable.ic_navi_uturn_left, NaviTextClient.turnIconRes(8))
    }

    @Test
    fun turnIconRes_straight() {
        assertEquals(R.drawable.ic_navi_straight, NaviTextClient.turnIconRes(9))
    }

    @Test
    fun turnIconRes_dest_10_and_15_bothMapToDest() {
        // 10/15 两种 ICON 都映射到"到达"图标
        assertEquals(R.drawable.ic_navi_dest, NaviTextClient.turnIconRes(10))
        assertEquals(R.drawable.ic_navi_dest, NaviTextClient.turnIconRes(15))
    }

    @Test
    fun turnIconRes_island_11_12_17_18_mapToIsland() {
        // 11/12/17/18 环岛 4 种 ICON 都映射到环岛图标
        assertEquals(R.drawable.ic_navi_island, NaviTextClient.turnIconRes(11))
        assertEquals(R.drawable.ic_navi_island, NaviTextClient.turnIconRes(12))
        assertEquals(R.drawable.ic_navi_island, NaviTextClient.turnIconRes(17))
        assertEquals(R.drawable.ic_navi_island, NaviTextClient.turnIconRes(18))
    }

    @Test
    fun turnIconRes_park_charge_tunnel() {
        assertEquals(R.drawable.ic_navi_park, NaviTextClient.turnIconRes(13))
        assertEquals(R.drawable.ic_navi_charge, NaviTextClient.turnIconRes(14))
        assertEquals(R.drawable.ic_navi_tunnel, NaviTextClient.turnIconRes(16))
    }

    @Test
    fun turnIconRes_uturnRight_and_slow() {
        assertEquals(R.drawable.ic_navi_uturn_right, NaviTextClient.turnIconRes(19))
        assertEquals(R.drawable.ic_navi_slow, NaviTextClient.turnIconRes(20))
    }

    @Test
    fun turnIconRes_unknown_returnsZero() {
        // 未知/缺省 ICON 返回 0（不显示图标）
        assertEquals(0, NaviTextClient.turnIconRes(0))
        assertEquals(0, NaviTextClient.turnIconRes(99))
        assertEquals(0, NaviTextClient.turnIconRes(-1))
    }

    @Test
    fun turnIconMirrored_islandLeftNeedFlip() {
        // 17/18 环岛左需要水平镜像
        assertTrue(NaviTextClient.turnIconMirrored(17))
        assertTrue(NaviTextClient.turnIconMirrored(18))
    }

    @Test
    fun turnIconMirrored_otherIconsDoNotNeedFlip() {
        assertFalse(NaviTextClient.turnIconMirrored(2))
        assertFalse(NaviTextClient.turnIconMirrored(3))
        assertFalse(NaviTextClient.turnIconMirrored(8))
        assertFalse(NaviTextClient.turnIconMirrored(11))
        assertFalse(NaviTextClient.turnIconMirrored(12))
    }

    @Test
    fun cameraTypeName_knownTypes() {
        assertEquals("测速", NaviTextClient.cameraTypeName(0))
        assertEquals("监控", NaviTextClient.cameraTypeName(1))
        assertEquals("闯红灯", NaviTextClient.cameraTypeName(2))
        assertEquals("违章拍照", NaviTextClient.cameraTypeName(3))
        assertEquals("公交专用", NaviTextClient.cameraTypeName(4))
        assertEquals("应急车道", NaviTextClient.cameraTypeName(5))
    }

    @Test
    fun cameraTypeName_unknownReturnsFallback() {
        assertEquals("电子眼", NaviTextClient.cameraTypeName(6))
        assertEquals("电子眼", NaviTextClient.cameraTypeName(-1))
        assertEquals("电子眼", NaviTextClient.cameraTypeName(99))
    }

    @Test
    fun turnIconRes_and_cameraTypeName_areIndependent() {
        // 多个不同 ICON 共享同一图标（如 10/15 都到 ic_navi_dest），
        // 但 ICON 9 自身到 ic_navi_straight 是不同的资源。
        val r10 = NaviTextClient.turnIconRes(10)
        val r15 = NaviTextClient.turnIconRes(15)
        val r9 = NaviTextClient.turnIconRes(9)
        assertEquals(r10, r15)
        assertNotEquals(r10, r9)
    }
}
