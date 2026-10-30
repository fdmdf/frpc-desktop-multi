package com.frpc.desktop.engine

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.data.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

class EngineManager(private val settings: SettingsStore) {

    fun bundledVersion(): String = runCatching {
        javaClass.getResourceAsStream("/engine/VERSION")
            ?.bufferedReader()?.use { it.readText().trim() }
            .orEmpty()
    }.getOrDefault("").ifBlank { "unknown" }

    fun engineFile(): File {
        val custom = settings.state.value.enginePath.trim()
        if (custom.isNotEmpty()) {
            val f = File(custom)
            if (f.isFile) return f
        }
        return File(AppPaths.binDir, AppPaths.engineFileName)
    }

    fun isUsingCustom(): Boolean {
        val custom = settings.state.value.enginePath.trim()
        return custom.isNotEmpty() && File(custom).isFile
    }

    fun ensureReady(): Result<File> {
        engineFile().takeIf { it.isFile && it.canExecute() }?.let { return Result.success(it) }

        val resource = "/engine/${AppPaths.engineFileName}"
        val input = javaClass.getResourceAsStream(resource)
            ?: return Result.failure(
                IllegalStateException("未找到内置引擎 $resource，且设置中没有可用的引擎路径"),
            )
        return runCatching {
            val target = File(AppPaths.binDir, AppPaths.engineFileName)
            input.use { ins -> target.outputStream().use { ins.copyTo(it) } }
            target.setExecutable(true)
            if (!AppPaths.isWindows) {
                ProcessBuilder("chmod", "+x", target.absolutePath).start().waitFor(3, TimeUnit.SECONDS)
            }
            target
        }
    }

    fun probeVersion(): String {
        val engine = engineFile()
        if (!engine.isFile) return bundledVersion()
        return runCatching {
            val process = ProcessBuilder(engine.absolutePath, "-v").redirectErrorStream(true).start()
            process.waitFor(3, TimeUnit.SECONDS)
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            text.lineSequence().firstOrNull { it.contains("frp") }?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: bundledVersion()
    }
}
