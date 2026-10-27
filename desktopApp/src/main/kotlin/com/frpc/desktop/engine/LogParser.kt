package com.frpc.desktop.engine

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** 代理状态（日志推断，admin API 不可用时兜底） */
enum class LogProxyPhase { PENDING, RUNNING, ERROR }

data class LogProxyState(val phase: LogProxyPhase, val error: String? = null)

sealed interface ProxyLogEvent {
    data class Update(val name: String, val state: LogProxyState) : ProxyLogEvent

    /** 访问者注册成功（frpc 一条日志列出全部），Admin API 不返回 visitor，只能靠日志 */
    data class VisitorsRegistered(val names: List<String>) : ProxyLogEvent

    /** 登录 frps 成功（P4：进程活着不等于连上了服务器） */
    data object LoginSuccess : ProxyLogEvent

    /** 登录 frps 失败（token 错 / 网络不通等），携带最后一次错误描述 */
    data class LoginFailed(val error: String) : ProxyLogEvent

    /** 控制连接断开：所有代理状态回到未知 */
    data object Reset : ProxyLogEvent
}

data class LogLine(
    val timeMs: Long,
    val level: LogLevel,
    /** frpc 输出的 [来源] 位置，如 service.go:214 */
    val tag: String,
    val message: String,
)

/**
 * frpc 控制台日志解析。真实格式（frp v0.71.x）：
 *   2026-10-24 08:00:00.123 [I] [sub/root.go:194] start frpc service for config file [...]
 * 兼容旧版的 `yyyy/MM/dd` 与 ANSI 颜色码。
 */
object FrpcLogParser {
    private val PATTERN = Regex(
        """^(\d{4}[-/]\d{2}[-/]\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([TDIWE])\] \[([^\]]*)] ?(.*)$""",
    )
    private val ANSI = Regex("""\u001B\[[;\d]*[A-Za-z]""")
    private val FORMAT_DASH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val FORMAT_SLASH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS")

    fun parse(raw: String, nowFallbackMs: Long = System.currentTimeMillis()): LogLine {
        val cleaned = ANSI.replace(raw.trimEnd(), "")
        val match = PATTERN.matchEntire(cleaned)
        if (match != null) {
            val (timestamp, levelChar, tag, message) = match.destructured
            val timeMs = runCatching {
                LocalDateTime.parse(timestamp, FORMAT_DASH).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.recoverCatching {
                LocalDateTime.parse(timestamp, FORMAT_SLASH).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrDefault(nowFallbackMs)
            return LogLine(timeMs, levelOf(levelChar), tag, message)
        }
        return LogLine(nowFallbackMs, LogLevel.INFO, "", cleaned)
    }

    /**
     * 状态接口的访问日志。frpc 的 NewRequestLogger 固定以 Info 打印
     * `http request: [...]` / `http response [...]`，没有关闭开关，
     * 而客户端每 4 秒轮询一次，默认在界面上隐藏这两行。
     */
    fun isAccessLog(message: String): Boolean =
        message.startsWith("http request: [") || message.startsWith("http response [")

    private fun levelOf(c: String): LogLevel = when (c) {
        "T" -> LogLevel.TRACE
        "D" -> LogLevel.DEBUG
        "W" -> LogLevel.WARN
        "E" -> LogLevel.ERROR
        else -> LogLevel.INFO
    }
}

/**
 * 从日志正文推断代理状态。frpc 0.7x 输出示例：
 *   proxy added: [ssh]
 *   [ssh] start proxy success
 *   [ssh] start error: proxy [ssh] already exists
 * 控制连接断开时输出 control writer is closing / client exit success。
 */
object ProxyLogParser {
    private val ADDED = Regex("""proxy added: \[([^\]]+)]""")
    private val STARTED = Regex("""\[([^\]]+)] start proxy success""")
    private val ERROR = Regex("""\[([^\]]+)] start error: (.+)""")
    private val VISITOR_ADDED = Regex("""visitor added: \[([^\]]*)]""")
    private val LOGIN_OK = Regex("""login to server success""")
    private val LOGIN_FAIL = Regex("""connect to server error: (.+)""")
    private val RESET_MARKERS = listOf("control writer is closing", "client exit success")

    fun parse(message: String): ProxyLogEvent? {
        // 登录事件优先判断：这两行不带 [代理名] 前缀，不会与代理事件混淆
        LOGIN_FAIL.find(message)?.let { return ProxyLogEvent.LoginFailed(it.groupValues[1].trim()) }
        if (LOGIN_OK.containsMatchIn(message)) return ProxyLogEvent.LoginSuccess
        ADDED.find(message)?.let { m ->
            return ProxyLogEvent.Update(m.groupValues[1], LogProxyState(LogProxyPhase.PENDING))
        }
        STARTED.find(message)?.let { m ->
            return ProxyLogEvent.Update(m.groupValues[1], LogProxyState(LogProxyPhase.RUNNING))
        }
        ERROR.find(message)?.let { m ->
            return ProxyLogEvent.Update(
                m.groupValues[1],
                LogProxyState(LogProxyPhase.ERROR, m.groupValues[2].trim()),
            )
        }
        // frpc: "visitor added: [a b c]"，一次列出全部已注册访问者
        VISITOR_ADDED.find(message)?.let { m ->
            val names = m.groupValues[1].split(' ').filter { it.isNotBlank() }
            if (names.isNotEmpty()) return ProxyLogEvent.VisitorsRegistered(names)
        }
        if (RESET_MARKERS.any { message.contains(it) }) return ProxyLogEvent.Reset
        return null
    }
}
