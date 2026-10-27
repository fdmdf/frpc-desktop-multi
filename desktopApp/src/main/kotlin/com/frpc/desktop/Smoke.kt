package com.frpc.desktop

import com.frpc.desktop.engine.FrpcSupervisor
import kotlin.system.exitProcess
import java.io.File

/**
 * 有上限地等待条件成立。
 *
 * 状态、代理列表、admin 可达性都由轮询协程异步更新（STATUS_INTERVAL_MS = 4s），
 * 用「固定 sleep 后取一次快照」的写法会和轮询周期赛跑。
 */
private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(200)
    }
    return condition()
}

/**
 * 无界面自检：`frpc-desktop --smoke <frpc.toml>`
 * 用真实引擎跑一次「启动 → 等状态 → 打印 admin 状态/资源 → 停止」，
 * 用于本地验证与 CI（不需要图形环境）。
 */
fun runSmoke(args: Array<String>) {
    val index = args.indexOf("--smoke")
    val tomlPath = args.getOrNull(index + 1)
    if (tomlPath == null) {
        println("[smoke] usage: --smoke <path-to-frpc.toml>")
        exitProcess(2)
    }
    val container = AppContainer()
    container.init()

    val engine = container.engine.ensureReady()
    println("[smoke] engine=${engine.map { it.absolutePath }.getOrElse { "UNAVAILABLE: ${it.message}" }}")
    engine.getOrElse { exitProcess(3) }

    val profile = container.profiles.importToml("smoke", File(tomlPath).readText())
    println("[smoke] profile server=${profile.serverSummary} proxies=${profile.config.proxies.size}")

    container.supervisor.start(profile)

    // 原先是「sleep 8s 后取一次快照」，而状态轮询间隔是 4s，取样点正好卡在第二次轮询的
    // 边界上 —— CI 上复现过一次：frpc 已 login success、代理也已 start success，
    // 但 admin reachable 仍是 false，被 CI 断言误判为失败。
    // 改成有上限地等待条件就绪，超时依旧判 false，判定语义不变，只是不再和轮询周期赛跑。
    waitUntil(20_000) { container.supervisor.state.value.phase == FrpcSupervisor.Phase.RUNNING }
    waitUntil(15_000) { container.supervisor.status.value?.all?.isNotEmpty() == true }
    val adminReady =
        if (container.supervisor.state.value.adminEnabled) {
            waitUntil(20_000) { container.supervisor.adminReachable.value }
        } else {
            false
        }

    val state = container.supervisor.state.value
    println("[smoke] phase=${state.phase} pid=${state.pid} exit=${state.error}")
    println("[smoke] admin enabled=${state.adminEnabled} port=${state.adminPort} reachable=$adminReady")
    container.supervisor.status.value?.all?.forEach {
        println("[smoke] proxy ${it.name} type=${it.type} status=${it.status} remote=${it.remoteAddr} err=${it.err}")
    }
    println("[smoke] metrics cpu=${container.supervisor.metrics.value?.cpuPercent} rss=${container.supervisor.metrics.value?.rssKb}KB threads=${container.supervisor.metrics.value?.threads}")
    println("[smoke] visitors=${container.supervisor.visitors.value.sorted()}")

    // 端到端验证：从 frps 的远程端口回连，确认真能穿到本地服务
    val tcpProxy = profile.config.proxies.firstOrNull { it.type == "tcp" && it.remotePort != null }
    if (tcpProxy?.remotePort != null) {
        runCatching {
            java.net.Socket("127.0.0.1", tcpProxy.remotePort!!).use { socket ->
                socket.soTimeout = 4_000
                socket.getOutputStream().apply {
                    write("ping-smoke".toByteArray())
                    flush()
                }
                val buffer = ByteArray(128)
                val read = socket.getInputStream().read(buffer)
                println("[smoke] forward :${tcpProxy.remotePort} -> " + String(buffer, 0, read))
            }
        }.onFailure { println("[smoke] forward FAILED: ${it.message}") }
    }

    container.supervisor.stop()
    waitUntil(15_000) { container.supervisor.state.value.phase == FrpcSupervisor.Phase.IDLE }
    println("[smoke] after-stop phase=${container.supervisor.state.value.phase}")
    println("[smoke] logs(tail):")
    container.supervisor.logsSnapshot().takeLast(10).forEach {
        println("    ${it.level} ${it.tag} ${it.message}")
    }
    exitProcess(0)
}
