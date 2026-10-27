package com.frpc.shared.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 端口占用检测的语义回归。
 *
 * 背景：早期实现是「不带 SO_REUSEADDR 试绑一次」，于是 frpc 停止后，
 * 它 admin 端口上残留在 TIME_WAIT 的连接会让检测在几十秒内一直报「被占用」——
 * 界面表现就是「停止后立刻再启动永远被拦下，等一分钟又好了」。
 */
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

    /**
     * 自己 stopping 完立刻再启动不得被拦：服务端侧主动关闭留下的 TIME_WAIT 不算占用。
     *
     * 监听者这里**必须**带 SO_REUSEADDR —— frpc 用的 Go `net.Listen` 就是这么绑的，
     * 于是它 accept 出来的连接也继承了这个选项，退出后留下的 TIME_WAIT 才允许被重新绑定。
     * 反过来讲：如果上一个监听者没带这个选项（Linux 要求「新旧两端都带」才可复用），
     * 那 frpc 自己也绑不上，此时报「被占用」是对的，不是误报。
     */
    @Test
    fun `上一轮连接残留 TIME_WAIT 不算占用`() {
        val port = freePort()
        val listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(HOST, port))
        }
        // 建一条真连接，再让「服务端」先关 —— 模拟 frpc 退出时关闭自己已建立的连接
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

    /** 找一个当前没人用的端口（有可能刚被别的测试摸过，多试几次） */
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
