package com.frpc.shared.ports

/**
 * 批量端口表达式解析：`8080`、`8080,8081`、`8080-8085`、`8000-8002,9000`。
 *
 * 设计约束（对齐 frpc-desktop 的批量端口行为）：
 * - 一次最多展开 [MAX_EXPAND] 条，防止用户输入 `1-65535` 直接把配置撑爆；
 * - 端口必须在 1..65535；
 * - 区间必须是升序，写反了直接报错而不是悄悄交换；
 * - 重复端口去重并保序，避免生成同名代理。
 *
 * 纯函数，桌面端与 Android 共用。
 */
object PortRange {

    const val MAX_EXPAND = 100
    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    enum class Reason {
        /** 输入为空 */
        EMPTY,
        /** 不是「数字」或「数字-数字」 */
        INVALID,
        /** 端口越界 */
        OUT_OF_RANGE,
        /** 区间起止写反 */
        REVERSED,
        /** 展开后超过上限 */
        TOO_MANY,
    }

    sealed interface ParseResult {
        data class Ok(val ports: List<Int>) : ParseResult
        data class Failed(val reason: Reason, val detail: String? = null) : ParseResult
    }

    /** 单端口（如 `8080`）返回 false；区间或多端口（如 `8080-8085`、`8080,8081`）返回 true */
    fun isBatch(expr: String): Boolean =
        expr.contains(',') || expr.contains('，') || expr.contains('-')

    /** 批量名称：`web` + 8080 → `web-8080`；已有同名后缀时不会产生歧义 */
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
