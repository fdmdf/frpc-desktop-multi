package com.frpc.desktop

import com.frpc.desktop.engine.FrpcSupervisor
import kotlin.system.exitProcess
import java.io.File

private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(200)
    }
    return condition()
}

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
