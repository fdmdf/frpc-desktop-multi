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
 * 端口占用检测。
 *
 * 判据是「frpc 现在能不能把它绑上」，而不是「这个端口有没有被碰过」：
 *
 * 1. 先试着连一下 127.0.0.1:<port>——连得上说明确实有程序在监听，这是唯一确定的占用信号；
 * 2. 连不上再自己绑一次，并且**必须带 SO_REUSEADDR**。
 *
 * 第 2 步不加这个选项时有个经典坑：frpc 退出后，它 admin 端口上已建立的连接会留下
 * TIME_WAIT（本地端点正好是 127.0.0.1:adminPort，内核保留几十秒），
 * 此时不带 SO_REUSEADDR 的 bind 一律失败 —— 表现就是
 * 「停止之后立刻再启动，永远提示端口被占用，等一分钟又正常」。
 * 而 Go 的 net.Listen 本身就带 SO_REUSEADDR，frpc 其实绑得上，
 * 所以检测必须和它对齐，否则就是假警报。
 */
object PortCheck {

    /** loopback 上握手要么成功要么立刻被拒，给个短超时只为兜住「监听队列满」这类情况 */
    private const val CONNECT_TIMEOUT_MS = 250

    /** frpc 的 admin API 只监听本机，默认按 127.0.0.1 探测 */
    fun isInUse(port: Int): Boolean = isInUse(port, "127.0.0.1")

    fun isInUse(port: Int, host: String): Boolean {
        if (port !in 1..65535) return false
        if (canConnect(port, host)) return true
        return !canBind(port, host)
    }

    /**
     * 等待端口被真正释放（停止后紧接着重启时用）。
     *
     * 进程收到 SIGKILL 到内核回收监听套接字之间有一小段窗口，
     * 不等这一步的话「停止→立刻启动」仍有概率撞上自己还没散干净的上一次实例。
     * 超时返回 false，调用方按「仍然占用」处理即可，不必把它当致命错误。
     */
    suspend fun awaitFree(port: Int, timeoutMs: Long = 3000, host: String = "127.0.0.1"): Boolean {
        if (port !in 1..65535) return true
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            if (!isInUse(port, host)) return true
            delay(100)
        }
        return !isInUse(port, host)
    }

    /** 连得上 = 确实有程序在监听。探测失败（被拒/超时/无路由）都当作没在监听 */
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

    /** JVM 跑在 Android 上（jvmCommon 同时喂桌面端与 Android，不能靠 AGP 的 BuildConfig 判断） */
    private val isAndroid: Boolean
        get() = runCatching { Class.forName("android.os.Build") != null }.getOrDefault(false)

    private const val LOOPBACK = "127.0.0.1"
    private const val PROBE_TIMEOUT_MS = 200
    private const val PROBE_THREADS = 48
    private const val MAX_SCAN_HOSTS = 4
    private const val SCAN_BUDGET_MS = 8000L
    /** 连接数多的机器上 netstat 要跑几秒，给足时间；真超时也不至于返回空（见 [exec]） */
    private const val EXEC_TIMEOUT_MS = 10_000L

    /** 先扫这些常用端口，保证即使全量扫描被时间预算截断，用户要找的服务也在里面 */
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
        // 解析类手段拿不到东西时（最典型：Android 10+ 起 /proc/net 不对应用开放，
        // 系统里也没有 netstat / ss）退化成主动探测，否则选择器永远空列表
        // macOS 的 lsof 在受限环境里可能一条都列不出来，同样允许退到探测
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
            // NAME 是最后一列，但它自己带空格（`*:8080 (LISTEN)`），按空白切开会把它劈成两列，
            // 所以从第 8 列开始整段拼回去，再截掉状态标记与 `->` 对端
            val name = cols.drop(8).joinToString(" ")
            val endpoint = name.substringBefore(" ").substringBefore("->")
            val (addr, port) = splitEndpoint(endpoint) ?: return@mapNotNull null
            val listening = proto == "udp" || line.contains("(LISTEN)")
            if (!listening) return@mapNotNull null
            ListeningPort(proto, addr, port, cols.getOrNull(1)?.toLongOrNull())
        }.toList()
    }

    // ---------------------------------------------------------------- 工具

    // ------------------------------------------------------------ 探测式兜底

    /**
     * 只有在「读表这条路根本不可用」时才去探测：
     * Android 10+ 的 /proc/net 是典型（文件还在，但读出来是空的 —— 权限被收走了），
     * 桌面 Linux 能正常读到，就没必要扫 6 万个端口。
     */
    private fun needConnectScan(): Boolean {
        if (isAndroid) return true
        if (isWindows || isMac) return false
        return !runCatching { File("/proc/net/tcp").canRead() }.getOrDefault(false)
    }

    /**
     * 主动探测：连得上就是有人在监听。
     *
     * 只能测 TCP（UDP 无连接，探不出来），所以结果一律标 tcp。
     * 分两轮：
     * 1. 常见端口 × 所有本机地址（含局域网地址，只绑了网卡的服务也能扫到）；
     * 2. 全量 1..65535 × loopback（绑 0.0.0.0 的服务在 loopback 上同样可达）。
     *
     * 关闭的端口会立刻回 RST，所以一轮其实很快；仍带时间预算兜底，
     * 超时就返回已经扫到的部分，宁可少列几个也不让用户干等。
     */
    internal fun scanByConnect(budgetMs: Long = SCAN_BUDGET_MS): List<ListeningPort> {
        val deadline = System.currentTimeMillis() + budgetMs
        val hosts = localHosts()
        val found = Collections.synchronizedMap(LinkedHashMap<String, ListeningPort>())
        val stop = AtomicBoolean(false)

        // 常见端口 × 各本机地址
        runProbe(deadline, stop, hosts.size * COMMON_PORTS.size) { i ->
            probe(found, hosts[i % hosts.size], COMMON_PORTS[i / hosts.size])
        }
        // 全量端口 × loopback
        runProbe(deadline, stop, 65535) { i -> probe(found, LOOPBACK, i + 1) }

        return found.values.toList()
    }

    private fun probe(sink: MutableMap<String, ListeningPort>, host: String, port: Int) {
        if (PortCheck.canConnect(port, host, PROBE_TIMEOUT_MS)) {
            sink["$host:$port"] = ListeningPort("tcp", "$host:$port", port, null)
        }
    }

    /** 固定线程池 + 原子游标分发；到点就置 stop，任务自己退出（不去 interrupt 正在 connect 的线程） */
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

    /** 本机地址：loopback 排第一，后面跟局域网 IPv4（只绑了网卡的服务也能探到） */
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

    /**
     * 跑外部命令取输出。
     *
     * 关键点是**必须一边等一边排空 stdout**：netstat -ano 在连接数多的机器上能吐出几 MB，
     * 管道缓冲区（几十 KB）一满它就卡在 write 上，这时候干等 waitFor 只会超时然后被我们杀掉
     * —— 表现就是端口列表永远是空的。所以这里开一个线程专门读，超时也把已读到的部分返回。
     */
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
            // 超时也返回已采集的部分：netstat 是按行输出的，截断最多少列几个，总比空列表强
            buffer.toString()
        } finally {
            process.destroy()
        }
    }.getOrNull()
}
