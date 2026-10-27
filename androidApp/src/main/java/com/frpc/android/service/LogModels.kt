package com.frpc.android.service

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** 代理状态（日志推断，Admin API 不可用时兜底） */
enum class LogProxyPhase { PENDING, RUNNING, ERROR }

data class LogProxyState(
    val phase: LogProxyPhase,
    val error: String? = null,
)

/** 日志中反映的代理状态事件 */
sealed interface ProxyLogEvent {
    data class Update(val name: String, val state: LogProxyState) : ProxyLogEvent

    /** 访问者注册成功（frpc 一条日志列出全部），admin API 不返回 visitor，只能靠日志 */
    data class VisitorsRegistered(val names: List<String>) : ProxyLogEvent

    /** 登录 frps 成功（P4：进程活着不等于连上了服务器） */
    data object LoginSuccess : ProxyLogEvent

    /** 登录 frps 失败（token 错 / 网络不通等） */
    data class LoginFailed(val error: String) : ProxyLogEvent

    /** 控制连接断开：所有代理状态回到未知 */
    data object Reset : ProxyLogEvent
}

/**
 * 从 frpc 日志正文推断代理状态（Admin API 不可用时的兜底）。
 * frpc 0.7x 输出示例：
 *   proxy added: [123]
 *   [123] start proxy success
 *   [123] start error: proxy [123] already exists
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
        // 登录事件优先：这两行不带 [代理名] 前缀
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

data class LogLine(
    val timeMs: Long,
    val level: LogLevel,
    /** frpc 输出的 [source] 位置，如 service.go:214 */
    val tag: String,
    val message: String,
)

/**
 * frpc 日志行解析。
 * frpc（golib logger）输出格式：2026-10-23 18:57:36.952 [I] [sub/root.go:194] message
 * - 日期分隔符兼容 - 与 /（不同 frp 版本）
 * - 防御性剥离 ANSI 颜色码（log.disablePrintColor=false 时理论上会带色）
 */
object FrpcLogParser {

    private val PATTERN = Regex(
        """^(\d{4}[-/]\d{2}[-/]\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([TDIWE])\] \[([^\]]*)] ?(.*)$""",
    )
    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
    private val FORMAT_DASH: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val FORMAT_SLASH: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS")

    fun parse(raw: String, nowFallbackMs: Long = System.currentTimeMillis()): LogLine {
        val cleaned = ANSI.replace(raw.trimEnd(), "")
        val match = PATTERN.matchEntire(cleaned)
        if (match != null) {
            val (timestamp, levelChar, tag, message) = match.destructured
            val timeMs = runCatching {
                LocalDateTime.parse(timestamp, FORMAT_DASH)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.recoverCatching {
                LocalDateTime.parse(timestamp, FORMAT_SLASH)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrDefault(nowFallbackMs)
            return LogLine(timeMs, levelOf(levelChar), tag, message)
        }
        return LogLine(nowFallbackMs, LogLevel.INFO, "", cleaned)
    }

    /**
     * 状态接口的访问日志。frpc 的 NewRequestLogger 固定以 Info 级别打印
     * `http request: [GET /api/status]` / `http response [200 OK]`，没有关闭开关，
     * 而 App 每 4 秒轮询一次状态接口，默认在界面上隐藏这两行以免刷屏。
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
