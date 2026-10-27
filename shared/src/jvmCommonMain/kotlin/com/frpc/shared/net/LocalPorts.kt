package com.frpc.shared.net

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** 本机一个正在监听的端口 */
data class ListeningPort(
    val protocol: String,
    val address: String,
    val port: Int,
    val pid: Long?,
) {
    val host: String get() = address.substringBeforeLast(':').trim('[', ']').ifBlank { "*" }
}

/**
 * 端口占用检测：真正试绑一次，绑不上即认为被占用。
 *
 * 只绑 127.0.0.1（frpc 的 webServer 默认也只听本机），不设 SO_REUSEADDR ——
 * 带上它会让 TIME_WAIT 状态下已释放的端口仍然「可绑」，检测就失去意义。
 */
object PortCheck {

    /** frpc 的 admin API 只监听本机，默认按 127.0.0.1 试绑 */
    fun isInUse(port: Int): Boolean = isInUse(port, "127.0.0.1")

    fun isInUse(port: Int, host: String): Boolean {
        if (port !in 1..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(host, port), 1)
            }
            false
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * 枚举本机正在监听的端口，供「本机端口」选择器使用。
 *
 * 三个平台各走一条最可靠的路子：
 * - Windows：`netstat -ano`（无需额外依赖）
 * - Linux / Android：直接读 `/proc/net/tcp*`、`/proc/net/udp*`（比解析 netstat 稳，也不依赖 ss）
 * - macOS：`lsof -nP -i`（系统自带）
 *
 * 任何一步失败都返回空列表，UI 降级为「手动输入」，不向用户抛异常。
 */
object LocalPorts {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    fun list(): List<ListeningPort> = runCatching {
        when {
            isWindows -> windows()
            isMac -> macos()
            else -> linux()
        }
    }.getOrDefault(emptyList())
        .filter { it.port in 1..65535 }
        .distinctBy { it.protocol to it.port }
        .sortedWith(compareBy({ it.port }, { it.protocol }))

    // ---------------------------------------------------------------- Windows

    private fun windows(): List<ListeningPort> {
        val out = exec(listOf("netstat", "-ano")) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) return@mapNotNull null
            val proto = cols[0].lowercase()
            if (proto != "tcp" && proto != "udp") return@mapNotNull null
            val listening = proto == "udp" || cols.getOrNull(3).equals("LISTENING", true) ||
                cols.any { it.equals("LISTENING", true) }
            if (!listening) return@mapNotNull null
            val (addr, port) = splitEndpoint(cols[1]) ?: return@mapNotNull null
            val pid = cols.lastOrNull()?.toLongOrNull()
            ListeningPort(proto, addr, port, pid)
        }.toList()
    }

    // ---------------------------------------------------------- Linux / Android

    private fun linux(): List<ListeningPort> {
        val result = mutableListOf<ListeningPort>()
        listOf("tcp" to true, "tcp6" to true, "udp" to false, "udp6" to false).forEach { (name, needListen) ->
            val file = File("/proc/net/$name")
            if (!file.isFile) return@forEach
            runCatching {
                file.readLines().drop(1).forEach { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 10) return@forEach
                    val proto = name.removeSuffix("6")
                    // TCP 只看 LISTEN(0A)；UDP 没有 LISTEN，取未连接的对端全零记录
                    if (needListen && cols[3] != "0A") return@forEach
                    if (!needListen && cols[2] != "00000000:0000" && cols[2] != "00000000000000000000000000000000:0000") {
                        return@forEach
                    }
                    val (hexAddr, hexPort) = cols[1].split(":").takeIf { it.size == 2 } ?: return@forEach
                    val port = hexPort.toIntOrNull(16) ?: return@forEach
                    val addr = decodeHexAddr(hexAddr, ipv6 = name.endsWith("6"))
                    result += ListeningPort(proto, "$addr:$port", port, null)
                }
            }
        }
        return result
    }

    /** /proc/net 里是小端十六进制，IPv4 每字节倒序，IPv6 每 4 字节一组倒序 */
    private fun decodeHexAddr(hex: String, ipv6: Boolean): String = runCatching {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }
        if (!ipv6 && bytes.size == 4) {
            bytes.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else if (ipv6 && bytes.size == 16) {
            val groups = mutableListOf<String>()
            for (i in 0 until 16 step 4) {
                val g = bytes.subList(i, i + 4).reversed()
                groups += ((g[0].toInt() and 0xFF shl 8) or (g[1].toInt() and 0xFF)).toString(16) +
                    ":" + ((g[2].toInt() and 0xFF shl 8) or (g[3].toInt() and 0xFF)).toString(16)
            }
            groups.joinToString(":").replace(Regex("(:0)+:(0:)+"), "::")
        } else {
            hex
        }
    }.getOrDefault(hex)

    // ---------------------------------------------------------------- macOS

    private fun macos(): List<ListeningPort> {
        val out = exec(listOf("lsof", "-nP", "-iTCP", "-iUDP")) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            if (line.startsWith("COMMAND")) return@mapNotNull null
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 9) return@mapNotNull null
            val proto = cols.firstOrNull { it.equals("TCP", true) || it.equals("UDP", true) }?.lowercase()
                ?: return@mapNotNull null
            val name = cols.last()
            val endpoint = name.substringBefore(" ").substringBefore("->")
            val (addr, port) = splitEndpoint(endpoint) ?: return@mapNotNull null
            val listening = proto == "udp" || line.contains("(LISTEN)")
            if (!listening) return@mapNotNull null
            ListeningPort(proto, addr, port, cols.getOrNull(1)?.toLongOrNull())
        }.toList()
    }

    // ---------------------------------------------------------------- 工具

    private fun splitEndpoint(text: String): Pair<String, Int>? {
        val idx = text.lastIndexOf(':')
        if (idx <= 0) return null
        val port = text.substring(idx + 1).trim().toIntOrNull() ?: return null
        val addr = text.substring(0, idx).trim()
        return addr to port
    }

    private fun exec(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            process.destroy()
        }
    }.getOrNull()
}
