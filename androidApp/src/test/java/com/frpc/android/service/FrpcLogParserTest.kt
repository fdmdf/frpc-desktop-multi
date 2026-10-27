package com.frpc.android.service

import kotlin.test.Test
import kotlin.test.assertEquals

/** frpc 日志解析测试（样本取自 frp v0.71.1 真实输出） */
class FrpcLogParserTest {

    @Test
    fun parseRealInfoLine() {
        val line = FrpcLogParser.parse(
            "2026-10-23 18:57:36.952 [I] [sub/root.go:194] start frpc service for config file [frpc-e2e.toml] with aggregated configuration",
            nowFallbackMs = 0,
        )
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("sub/root.go:194", line.tag)
        assertEquals(
            "start frpc service for config file [frpc-e2e.toml] with aggregated configuration",
            line.message,
        )
        // 本地时区解析，非 0 即成功
        assertEquals(true, line.timeMs > 0)
    }

    @Test
    fun parseWarnAndErrorLines() {
        val warn = FrpcLogParser.parse(
            "2026-10-23 18:57:39.030 [W] [client/service.go:346] connect to server error: dial tcp 127.0.0.1:7000: connectex: No connection could be made",
        )
        assertEquals(LogLevel.WARN, warn.level)
        assertEquals("client/service.go:346", warn.tag)
        assertEquals(true, warn.message.startsWith("connect to server error"))

        val error = FrpcLogParser.parse("2026-10-23 18:57:39.030 [E] [client/service.go:100] login to the server failed")
        assertEquals(LogLevel.ERROR, error.level)
        assertEquals("login to the server failed", error.message)
    }

    @Test
    fun parseSlashDateFormat() {
        val line = FrpcLogParser.parse("2026/10/23 18:57:36.952 [D] [heartbeat.go:51] send heartbeat to server")
        assertEquals(LogLevel.DEBUG, line.level)
        assertEquals("send heartbeat to server", line.message)
    }

    @Test
    fun stripsAnsiColors() {
        val line = FrpcLogParser.parse(
            "\u001B[32m2026-10-23 18:57:36.952 [I] [sub/root.go:194] hello\u001B[0m",
        )
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("hello", line.message)
    }

    @Test
    fun unparsableLineFallsBackToInfo() {
        val line = FrpcLogParser.parse("some raw output without format", nowFallbackMs = 12345)
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("some raw output without format", line.message)
        assertEquals(12345, line.timeMs)
    }

    /** 状态接口轮询日志（frp v0.71.1 pkg/util/http/middleware.go 的真实输出）应被识别并默认隐藏 */
    @Test
    fun detectStatusEndpointAccessLog() {
        val request = FrpcLogParser.parse(
            "2026-10-23 18:57:44.101 [I] [http/middleware.go:33] http request: [GET /api/status]",
        )
        val response = FrpcLogParser.parse(
            "2026-10-23 18:57:44.104 [I] [http/middleware.go:33] http response [200 OK]",
        )
        assertEquals(true, FrpcLogParser.isAccessLog(request.message))
        assertEquals(true, FrpcLogParser.isAccessLog(response.message))
        // 普通业务日志不能被误杀
        assertEquals(false, FrpcLogParser.isAccessLog("[ssh] start proxy success"))
        assertEquals(false, FrpcLogParser.isAccessLog("login to server success, get run id [abc]"))
        assertEquals(false, FrpcLogParser.isAccessLog("http request failed"))
    }
}
