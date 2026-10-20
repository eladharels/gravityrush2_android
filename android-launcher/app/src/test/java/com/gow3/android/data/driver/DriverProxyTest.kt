package com.gow3.android.data.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverProxyTest {

    @Test
    fun `effective prefix resolution follows explicit none then stored then language default`() {
        // 显式直连永远优先，语言默认不得覆盖它。
        assertEquals("", DriverProxy.effectivePrefix(DriverProxy.NONE, "zh-CN"))
        // 已存储值优先于语言默认。
        assertEquals("https://ghfast.top/", DriverProxy.effectivePrefix("https://ghfast.top/", "zh-CN"))
        // 未选择：中文默认镜像，非中文直连。
        assertEquals(DriverProxy.DEFAULT_MIRROR, DriverProxy.effectivePrefix(null, "zh-CN"))
        assertEquals(DriverProxy.DEFAULT_MIRROR, DriverProxy.effectivePrefix("", "zh"))
        assertEquals("", DriverProxy.effectivePrefix(null, "en-US"))
        assertEquals("", DriverProxy.effectivePrefix(null, null))
    }

    @Test
    fun `apply concatenates the prefix and never proxies an empty one`() {
        val url = "https://github.com/a/b/releases/download/v1/d.zip"
        assertEquals(url, DriverProxy.apply(url, ""))
        assertEquals("https://ghproxy.net/$url", DriverProxy.apply(url, "https://ghproxy.net/"))
        assertEquals("https://ghproxy.net/$url", DriverProxy.apply(url, "https://ghproxy.net"))
    }

    @Test
    fun `normalize completes the trailing slash`() {
        assertEquals("https://my.mirror/prefix/", DriverProxy.normalize("https://my.mirror/prefix"))
        assertEquals("https://my.mirror/prefix/", DriverProxy.normalize("https://my.mirror/prefix/"))
    }

    @Test
    fun `built in mirror table is non empty and slash terminated`() {
        assertTrue(DriverProxy.BUILT_IN.isNotEmpty())
        assertTrue(DriverProxy.BUILT_IN.all { it.endsWith("/") && it.startsWith("https://") })
        assertTrue(DriverProxy.DEFAULT_MIRROR in DriverProxy.BUILT_IN)
    }
}
