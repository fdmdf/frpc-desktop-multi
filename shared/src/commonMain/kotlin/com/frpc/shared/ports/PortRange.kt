package com.frpc.shared.ports

object PortRange {

    const val MAX_EXPAND = 100
    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    enum class Reason {
        EMPTY,
        INVALID,
        OUT_OF_RANGE,
        REVERSED,
        TOO_MANY,
    }

    sealed interface ParseResult {
        data class Ok(val ports: List<Int>) : ParseResult
        data class Failed(val reason: Reason, val detail: String? = null) : ParseResult
    }

    fun isBatch(expr: String): Boolean =
        expr.contains(',') || expr.contains('，') || expr.contains('-')

    fun proxyName(base: String, port: Int): String {
        val trimmed = base.trim().ifBlank { "proxy" }
        return "$trimmed-$port"
    }

    fun parse(expr: String, maxExpand: Int = MAX_EXPAND): ParseResult {
        val tokens = expr.split(',', '，', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ParseResult.Failed(Reason.EMPTY)

        val ports = LinkedHashSet<Int>()
        for (token in tokens) {
            val dash = token.indexOf('-')
            if (dash < 0) {
                val port = token.toIntOrNull() ?: return ParseResult.Failed(Reason.INVALID, token)
                if (port !in PORT_MIN..PORT_MAX) {
                    return ParseResult.Failed(Reason.OUT_OF_RANGE, token)
                }
                ports += port
            } else {
                val start = token.substring(0, dash).trim().toIntOrNull()
                    ?: return ParseResult.Failed(Reason.INVALID, token)
                val end = token.substring(dash + 1).trim().toIntOrNull()
                    ?: return ParseResult.Failed(Reason.INVALID, token)
                if (start !in PORT_MIN..PORT_MAX || end !in PORT_MIN..PORT_MAX) {
                    return ParseResult.Failed(Reason.OUT_OF_RANGE, token)
                }
                if (start > end) return ParseResult.Failed(Reason.REVERSED, token)
                if (end - start + 1 > maxExpand - ports.size) {
                    return ParseResult.Failed(Reason.TOO_MANY, token)
                }
                for (p in start..end) ports += p
            }
            if (ports.size > maxExpand) return ParseResult.Failed(Reason.TOO_MANY, token)
        }
        return ParseResult.Ok(ports.toList())
    }
}
