package com.frpc.shared.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortRangeTest {

    private fun ok(expr: String) = (PortRange.parse(expr) as PortRange.ParseResult.Ok).ports

    private fun failed(expr: String) = PortRange.parse(expr) as PortRange.ParseResult.Failed

    @Test
    fun singlePort() {
        assertEquals(listOf(8080), ok("8080"))
        assertEquals(listOf(8080), ok(" 8080 "))
        assertTrue(!PortRange.isBatch("8080"))
    }

    @Test
    fun commaList() {
        assertEquals(listOf(8080, 8081, 9000), ok("8080,8081,9000"))
        assertEquals(listOf(8080, 8081), ok("8080，8081"))
        assertTrue(PortRange.isBatch("8080,8081"))
    }

    @Test
    fun range() {
        assertEquals(listOf(6001, 6002, 6003), ok("6001-6003"))
        assertEquals(listOf(8000, 8001, 9000), ok("8000-8001,9000"))
        assertTrue(PortRange.isBatch("8000-8001"))
    }

    @Test
    fun dedupeKeepsOrder() {
        assertEquals(listOf(80, 81), ok("80,81,80"))
    }

    @Test
    fun rejectsInvalid() {
        assertEquals(PortRange.Reason.EMPTY, failed("").reason)
        assertEquals(PortRange.Reason.INVALID, failed("abc").reason)
        assertEquals(PortRange.Reason.INVALID, failed("80-").reason)
        assertEquals(PortRange.Reason.INVALID, failed("80-90-100").reason)
        assertEquals(PortRange.Reason.REVERSED, failed("9000-8000").reason)
        assertEquals(PortRange.Reason.OUT_OF_RANGE, failed("0").reason)
        assertEquals(PortRange.Reason.OUT_OF_RANGE, failed("70000").reason)
    }

    @Test
    fun rejectsTooMany() {
        // 1-65535 会被上限拦住，避免一次生成几万条代理
        assertEquals(PortRange.Reason.TOO_MANY, failed("1-65535").reason)
        assertEquals(PortRange.MAX_EXPAND, ok("1000-1099").size)
        assertEquals(PortRange.Reason.TOO_MANY, failed("1000-1100").reason)
    }

    @Test
    fun proxyNameSuffix() {
        assertEquals("web-8080", PortRange.proxyName("web", 8080))
        assertEquals("proxy-22", PortRange.proxyName("  ", 22))
    }
}
