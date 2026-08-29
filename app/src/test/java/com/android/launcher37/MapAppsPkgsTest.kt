package com.android.launcher37

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapApps.PKGS] 单元测试。
 *
 * `PKGS` 是 FYT 平台支持的三家车机地图包名清单，优先级在测试中固定下来，
 * 避免无意识重排导致 `detect` 行为变化。
 */
class MapAppsPkgsTest {

    @Test
    fun pkgs_containsExpectedThreeMaps() {
        assertNotNull(MapApps.PKGS)
        assertEquals(3, MapApps.PKGS.size)
    }

    @Test
    fun pkgs_priorityOrderIsGaodeTencentBaidu() {
        assertEquals("com.autonavi.amapauto", MapApps.PKGS[0])   // 高德
        assertEquals("com.tencent.wecarnavi", MapApps.PKGS[1])  // 腾讯
        assertEquals("com.baidu.naviauto", MapApps.PKGS[2])     // 百度
    }

    @Test
    fun pkgs_allNonEmpty() {
        for (p in MapApps.PKGS) {
            assertNotNull(p)
            assertTrue("empty package: $p", p.isNotEmpty())
        }
    }

    @Test
    fun pkgs_noDuplicates() {
        val seen = HashSet<String>()
        for (p in MapApps.PKGS) {
            assertTrue("duplicate package: $p", seen.add(p))
        }
    }
}
