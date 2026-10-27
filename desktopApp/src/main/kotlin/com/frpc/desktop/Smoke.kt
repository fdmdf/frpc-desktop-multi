package com.frpc.desktop

import kotlin.system.exitProcess
import java.io.File

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
    Thread.sleep(8_000)

    val state = container.supervisor.state.value
    println("[smoke] phase=${state.phase} pid=${state.pid} exit=${state.error}")
    println("[smoke] admin enabled=${state.adminEnabled} port=${state.adminPort} reachable=${container.supervisor.adminReachable.value}")
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
    Thread.sleep(3_000)
    println("[smoke] after-stop phase=${container.supervisor.state.value.phase}")
    println("[smoke] logs(tail):")
    container.supervisor.logsSnapshot().takeLast(10).forEach {
        println("    ${it.level} ${it.tag} ${it.message}")
    }
    exitProcess(0)
}
