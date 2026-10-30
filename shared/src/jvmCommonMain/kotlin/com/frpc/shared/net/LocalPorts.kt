package com.frpc.shared.net

import kotlinx.coroutines.delay
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ListeningPort(
    val protocol: String,
    val address: String,
    val port: Int,
    val pid: Long?,
) {
    val host: String get() = address.substringBeforeLast(':').trim('[', ']').ifBlank { "*" }
}

object PortCheck {

    private const val CONNECT_TIMEOUT_MS = 250

    fun isInUse(port: Int): Boolean = isInUse(port, "127.0.0.1")

    fun isInUse(port: Int, host: String): Boolean {
        if (port !in 1..65535) return false
        if (canConnect(port, host)) return true
        return !canBind(port, host)
    }

    suspend fun awaitFree(port: Int, timeoutMs: Long = 3000, host: String = "127.0.0.1"): Boolean {
        if (port !in 1..65535) return true
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            if (!isInUse(port, host)) return true
            delay(100)
        }
        return !isInUse(port, host)
    }

    internal fun canConnect(
        port: Int,
        host: String,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun canBind(port: Int, host: String): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host, port), 1)
        }
        true
    } catch (_: Exception) {
        false
    }
}

object LocalPorts {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private val isAndroid: Boolean
        get() = runCatching { Class.forName("android.os.Build") != null }.getOrDefault(false)

    private const val LOOPBACK = "127.0.0.1"
    private const val PROBE_TIMEOUT_MS = 200
    private const val PROBE_THREADS = 48
    private const val MAX_SCAN_HOSTS = 4
    private const val SCAN_BUDGET_MS = 8000L
    private const val EXEC_TIMEOUT_MS = 10_000L

    private val COMMON_PORTS = intArrayOf(
        21, 22, 23, 25, 53, 80, 110, 143, 443, 445, 465, 587, 993, 995,
        1080, 1433, 1521, 2049, 3128, 3306, 3389, 5000, 5001, 5060, 5222, 5432,
        5555, 5900, 5984, 6379, 6443, 7000, 7400, 8000, 8008, 8009, 8022, 8080,
        8081, 8082, 8088, 8090, 8096, 8123, 8443, 8500, 8880, 8888, 9000, 9090,
        9091, 9200, 9418, 10000, 11211, 27017,
    )

    fun list(): List<ListeningPort> {
        val parsed = runCatching {
            when {
                isWindows -> windows()
                isMac -> macos()
                else -> linux()
            }
        }.getOrDefault(emptyList())
        val scanned = if (parsed.isEmpty() && (needConnectScan() || isMac)) {
            runCatching { scanByConnect() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return (parsed + scanned)
            .filter { it.port in 1..65535 }
            .distinctBy { it.protocol to it.port }
            .sortedWith(compareBy({ it.port }, { it.protocol }))
    }

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

    private fun macos(): List<ListeningPort> {
        val out = exec(listOf("lsof", "-nP", "-iTCP", "-iUDP")) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            if (line.startsWith("COMMAND")) return@mapNotNull null
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 9) return@mapNotNull null
            val proto = cols.firstOrNull { it.equals("TCP", true) || it.equals("UDP", true) }?.lowercase()
                ?: return@mapNotNull null
            val name = cols.drop(8).joinToString(" ")
            val endpoint = name.substringBefore(" ").substringBefore("->")
            val (addr, port) = splitEndpoint(endpoint) ?: return@mapNotNull null
            val listening = proto == "udp" || line.contains("(LISTEN)")
            if (!listening) return@mapNotNull null
            ListeningPort(proto, addr, port, cols.getOrNull(1)?.toLongOrNull())
        }.toList()
    }

    private fun needConnectScan(): Boolean {
        if (isAndroid) return true
        if (isWindows || isMac) return false
        return !runCatching { File("/proc/net/tcp").canRead() }.getOrDefault(false)
    }

    internal fun scanByConnect(budgetMs: Long = SCAN_BUDGET_MS): List<ListeningPort> {
        val deadline = System.currentTimeMillis() + budgetMs
        val hosts = localHosts()
        val found = Collections.synchronizedMap(LinkedHashMap<String, ListeningPort>())
        val stop = AtomicBoolean(false)

        runProbe(deadline, stop, hosts.size * COMMON_PORTS.size) { i ->
            probe(found, hosts[i % hosts.size], COMMON_PORTS[i / hosts.size])
        }
        runProbe(deadline, stop, 65535) { i -> probe(found, LOOPBACK, i + 1) }

        return found.values.toList()
    }

    private fun probe(sink: MutableMap<String, ListeningPort>, host: String, port: Int) {
        if (PortCheck.canConnect(port, host, PROBE_TIMEOUT_MS)) {
            sink["$host:$port"] = ListeningPort("tcp", "$host:$port", port, null)
        }
    }

    private fun runProbe(
        deadline: Long,
        stop: AtomicBoolean,
        total: Int,
        body: (Int) -> Unit,
    ) {
        if (total <= 0 || stop.get()) return
        val threads = PROBE_THREADS.coerceAtMost(total)
        val cursor = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val workers = (0 until threads).map {
                executor.submit {
                    while (!stop.get()) {
                        val i = cursor.getAndIncrement()
                        if (i >= total) return@submit
                        runCatching { body(i) }
                        if (System.currentTimeMillis() > deadline) stop.set(true)
                    }
                }
            }
            workers.forEach { runCatching { it.get() } }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun localHosts(): List<String> {
        val result = mutableListOf(LOOPBACK)
        runCatching {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (nics.hasMoreElements()) {
                val nic = nics.nextElement()
                if (!nic.isUp || nic.isLoopback) continue
                val addrs = nic.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && result.size < MAX_SCAN_HOSTS) {
                        result += addr.hostAddress.orEmpty()
                    }
                }
            }
        }
        return result.distinct()
    }

    private fun splitEndpoint(text: String): Pair<String, Int>? {
        val idx = text.lastIndexOf(':')
        if (idx <= 0) return null
        val port = text.substring(idx + 1).trim().toIntOrNull() ?: return null
        val addr = text.substring(0, idx).trim()
        return addr to port
    }

    private fun exec(command: List<String>, timeoutMs: Long = EXEC_TIMEOUT_MS): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val buffer = StringBuilder()
        val drainer = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(8192)
                    while (true) {
                        val read = reader.read(chunk)
                        if (read < 0) break
                        buffer.append(chunk, 0, read)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            drainer.join(1000)
            buffer.toString()
        } finally {
            process.destroy()
        }
    }.getOrNull()
}
