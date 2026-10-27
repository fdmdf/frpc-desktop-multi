package com.frpc.shared.net

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「本机端口」选择器能不能真的列出在监听的端口。
 *
 * 之前 Android 上一直是空列表：/proc/net 从 Android 10 起不对应用开放，
 * 系统里也没有 netstat / ss，读表这条路整个失效。
 * 所以这里两条路都测：读表（[LocalPorts.list]）和探测兜底（[LocalPorts.scanByConnect]）。
 */
class LocalPortsTest {

    /**
     * 读表路径：起一个监听套接字，list() 里必须能找到它。
     * 端口取 COMMON 段里当前空闲的那个 —— 探测兜底的第一轮只扫常见端口，
     * 这样第二条用例就不用等全量扫描跑完。
     */
    @Test
    fun list_containsOwnListeningPort() {
        val server = bindCommonPort() ?: return
        server.use {
            val port = it.localPort
            val found = LocalPorts.list().any { item -> item.port == port }
            assertTrue(found, "list() 没有列出本机正在监听的端口 $port")
        }
    }

    /** 探测兜底：/proc/net 读不到时（Android 10+）走这条路，同样必须能扫到 */
    @Test
    fun scanByConnect_findsListeningPort() {
        val server = bindCommonPort() ?: return
        server.use {
            val port = it.localPort
            val found = LocalPorts.scanByConnect(SCAN_BUDGET_MS)
                .any { item -> item.port == port }
            assertTrue(found, "connect 探测没有扫到本机正在监听的端口 $port")
        }
    }

    /** 探测不该把没在监听的端口也报出来（连不上的一律不算） */
    @Test
    fun scanByConnect_ignoresClosedPort() {
        val closed = findFreePort() ?: return
        val found = LocalPorts.scanByConnect(SCAN_BUDGET_MS)
            .any { item -> item.port == closed }
        assertTrue(!found, "端口 $closed 没人监听，却被当成在监听")
    }

    /** 找一个当前空闲的常见端口并占住；都被占用时返回 null（跳过，不算失败） */
    private fun bindCommonPort(): ServerSocket? {
        for (port in COMMON_CANDIDATES) {
            runCatching {
                val socket = ServerSocket(port)
                if (socket.localPort == port) return socket
                socket.close()
            }
        }
        return null
    }

    private fun findFreePort(): Int? = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrNull()

    private companion object {
        const val SCAN_BUDGET_MS = 4000L
        // 取自 LocalPorts.COMMON_PORTS：探测第一轮就扫这些，用例不必等全量扫描跑完
        val COMMON_CANDIDATES = intArrayOf(8123, 8500, 8880, 8008, 8009, 8090)
    }
}
