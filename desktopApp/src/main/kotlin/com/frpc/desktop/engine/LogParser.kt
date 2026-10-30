package com.frpc.desktop.engine

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

enum class LogProxyPhase { PENDING, RUNNING, ERROR }

data class LogProxyState(val phase: LogProxyPhase, val error: String? = null)

sealed interface ProxyLogEvent {
    data class Update(val name: String, val state: LogProxyState) : ProxyLogEvent

    data class VisitorsRegistered(val names: List<String>) : ProxyLogEvent

    data object LoginSuccess : ProxyLogEvent

    data class LoginFailed(val error: String) : ProxyLogEvent

    data object Reset : ProxyLogEvent
}

data class LogLine(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

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

object ProxyLogParser {
    private val ADDED = Regex("""proxy added: \[([^\]]+)]""")
    private val STARTED = Regex("""\[([^\]]+)] start proxy success""")
    private val ERROR = Regex("""\[([^\]]+)] start error: (.+)""")
    private val VISITOR_ADDED = Regex("""visitor added: \[([^\]]*)]""")
    private val LOGIN_OK = Regex("""login to server success""")
    private val LOGIN_FAIL = Regex("""connect to server error: (.+)""")
    private val RESET_MARKERS = listOf("control writer is closing", "client exit success")

    fun parse(message: String): ProxyLogEvent? {
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
        VISITOR_ADDED.find(message)?.let { m ->
            val names = m.groupValues[1].split(' ').filter { it.isNotBlank() }
            if (names.isNotEmpty()) return ProxyLogEvent.VisitorsRegistered(names)
        }
        if (RESET_MARKERS.any { message.contains(it) }) return ProxyLogEvent.Reset
        return null
    }
}
