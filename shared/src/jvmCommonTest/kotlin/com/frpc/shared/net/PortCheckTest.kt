package com.frpc.shared.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortCheckTest {

    @Test
    fun `真的有人在监听时算占用`() {
        val port = freePort()
        ServerSocket().use { listener ->
            listener.reuseAddress = false
            listener.bind(InetSocketAddress(HOST, port))
            assertTrue(PortCheck.isInUse(port), "端口上有监听者，应当判定为被占用")
        }
    }

    @Test
    fun `完全没人用的端口算空闲`() {
        val port = freePort()
        assertFalse(PortCheck.isInUse(port), "空端口应当判定为未占用")
    }

    @Test
    fun `上一轮连接残留 TIME_WAIT 不算占用`() {
        val port = freePort()
        val listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(HOST, port))
        }
        val client = Socket().apply { connect(InetSocketAddress(HOST, port), 1000) }
        val accepted = listener.accept()
        accepted.close()
        client.close()
        listener.close()
        waitUntil(3000) { !canConnect(port) }

        assertFalse(
            PortCheck.isInUse(port),
            "连接已断开、监听者也已关闭，只剩 TIME_WAIT 时不该判定为被占用",
        )
    }

    @Test
    fun `awaitFree 能等到端口释放`() {
        val port = freePort()
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(HOST, port))
        }
        listener.close()
        assertTrue(runBlockingFreeAwait(port), "端口释放后 awaitFree 应当为 true")
    }

    private fun runBlockingFreeAwait(port: Int): Boolean =
        kotlinx.coroutines.runBlocking { PortCheck.awaitFree(port, timeoutMs = 2000) }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, port), 250) }
        true
    } catch (_: Exception) {
        false
    }

    private fun freePort(): Int {
        repeat(5) {
            val port = ServerSocket().use { s ->
                s.bind(InetSocketAddress(HOST, 0))
                s.localPort
            }
            if (!PortCheck.isInUse(port)) return port
        }
        error("找不到空闲端口")
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
