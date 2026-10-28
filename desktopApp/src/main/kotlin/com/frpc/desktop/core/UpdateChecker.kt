package com.frpc.desktop.core

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI

/**
 * 检查 GitHub 上有没有新版本。**阻塞方法，必须在后台线程调用**（托盘菜单回调在 EDT 上）。
 *
 * 只取 releases/latest 的 tag_name，不引 JSON 依赖（桌面端没引 kotlinx.serialization
 * 之外的解析库，为一个版本号拉一棵依赖树不值得），用正则抠字段足够稳。
 */
object UpdateChecker {
    private const val API_LATEST =
        "https://api.github.com/repos/fdmdf/frpc-desktop-multi/releases/latest"
    const val RELEASES_PAGE = "https://github.com/fdmdf/frpc-desktop-multi/releases/latest"
    private const val TIMEOUT_MS = 8_000

    enum class State { UP_TO_DATE, AVAILABLE, FAILED }

    data class Result(
        val state: State,
        /** 远端版本号（不带前导 v）；失败时为 null */
        val version: String? = null,
    )

    fun check(current: String): Result = runCatching {
        val conn = URI(API_LATEST).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            // GitHub 对没有 UA 的请求直接 403
            conn.setRequestProperty("User-Agent", "Frpc-Android-Multi/${current.ifBlank { "dev" }}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return Result(State.FAILED)
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val remote = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?.groupValues?.get(1)?.trim()?.trimStart('v', 'V')
                ?: return Result(State.FAILED)
            if (compare(remote, current.trimStart('v', 'V')) > 0) {
                Result(State.AVAILABLE, remote)
            } else {
                Result(State.UP_TO_DATE, remote)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(Result(State.FAILED))

    /** 逐段比较数字；非数字段退化为字符串比较。返回 >0 表示 a 更新 */
    private fun compare(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: "0"
            val y = pb.getOrNull(i) ?: "0"
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }
}
