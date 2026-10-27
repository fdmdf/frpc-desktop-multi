package com.frpc.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 代理状态日志推断测试（样本取自 frpc 0.71.1 真实输出） */
class ProxyLogParserTest {

    @Test
    fun parseProxyAdded() {
        val event = ProxyLogParser.parse(
            "[9fb017f1cc6095e5] proxy added: [123]",
        )
        assertTrue(event is ProxyLogEvent.Update)
        event as ProxyLogEvent.Update
        assertEquals("123", event.name)
        assertEquals(LogProxyPhase.PENDING, event.state.phase)
    }

    @Test
    fun parseStartSuccess() {
        val event = ProxyLogParser.parse(
            "[9fb017f1cc6095e5] [123] start proxy success",
        )
        assertTrue(event is ProxyLogEvent.Update)
        event as ProxyLogEvent.Update
        assertEquals("123", event.name)
        assertEquals(LogProxyPhase.RUNNING, event.state.phase)
    }

    @Test
    fun parseStartError() {
        val event = ProxyLogParser.parse(
            "[a117eb7951b598dc] [test-tcp] start error: proxy [test-tcp] already exists",
        )
        assertTrue(event is ProxyLogEvent.Update)
        event as ProxyLogEvent.Update
        assertEquals("test-tcp", event.name)
        assertEquals(LogProxyPhase.ERROR, event.state.phase)
        assertEquals("proxy [test-tcp] already exists", event.state.error)
    }

    @Test
    fun parseControlClosingResets() {
        assertEquals(ProxyLogEvent.Reset, ProxyLogParser.parse("[x] control writer is closing"))
        assertEquals(ProxyLogEvent.Reset, ProxyLogParser.parse("[x] client exit success"))
    }

    @Test
    fun parseVisitorsRegistered() {
        val single = ProxyLogParser.parse("[8d123767a4dbc91d] visitor added: [test]")
        assertTrue(single is ProxyLogEvent.VisitorsRegistered)
        assertEquals(listOf("test"), (single as ProxyLogEvent.VisitorsRegistered).names)

        val multiple = ProxyLogParser.parse(
            "[8d123767a4dbc91d] visitor added: [e2e-stcp-visitor e2e-sudp-visitor e2e-xtcp-visitor]",
        )
        assertEquals(
            listOf("e2e-stcp-visitor", "e2e-sudp-visitor", "e2e-xtcp-visitor"),
            (multiple as ProxyLogEvent.VisitorsRegistered).names,
        )
    }

    @Test
    fun unrelatedLineIgnored() {
        assertNull(ProxyLogParser.parse("start frpc service for config file [x] with aggregated configuration"))
        // 登录成功现在是显式事件（P4：主页需要区分「运行中」与「未连上」）
        assertEquals(ProxyLogEvent.LoginSuccess, ProxyLogParser.parse("login to server success, get run id [9fb017f1cc6095e5]"))
    }

    @Test
    fun loginFailureParsed() {
        val event = ProxyLogParser.parse("connect to server error: token in login doesn't match token from configuration")
        assertTrue(event is ProxyLogEvent.LoginFailed)
        assertTrue(event.error.contains("token"))
    }
}
