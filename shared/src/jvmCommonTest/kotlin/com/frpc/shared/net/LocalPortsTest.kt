package com.frpc.shared.net

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalPortsTest {

    @Test
    fun list_containsOwnListeningPort() {
        val server = bindCommonPort() ?: return
        server.use {
            val port = it.localPort
            var found = LocalPorts.list().any { item -> item.port == port }
            if (!found) {
                Thread.sleep(500)
                found = LocalPorts.list().any { item -> item.port == port }
            }
            assertTrue(found, "list() 没有列出本机正在监听的端口 $port")
        }
    }

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

    @Test
    fun scanByConnect_ignoresClosedPort() {
        val closed = findFreePort() ?: return
        val found = LocalPorts.scanByConnect(SCAN_BUDGET_MS)
            .any { item -> item.port == closed }
        assertTrue(!found, "端口 $closed 没人监听，却被当成在监听")
    }

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
        val COMMON_CANDIDATES = intArrayOf(8123, 8500, 8880, 8008, 8009, 8090)
    }
}
