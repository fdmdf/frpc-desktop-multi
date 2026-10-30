package com.frpc.desktop.core

import java.io.File

object StartupTrace {

    private val target: File? = runCatching {
        System.getProperty("frpc.startupTrace")?.let { value ->
            if (value.isBlank() || value == "1") File(AppPaths.home, "startup.log") else File(value)
        }
    }.getOrNull()

    private val startedAt: Long = ProcessHandle.current().info().startInstant()
        .map { it.toEpochMilli() }
        .orElse(System.currentTimeMillis())

    fun mark(name: String) {
        val file = target ?: return
        runCatching { file.appendText("${System.currentTimeMillis() - startedAt}\t$name\n") }
    }
}
