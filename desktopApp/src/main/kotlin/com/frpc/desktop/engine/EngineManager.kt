package com.frpc.desktop.engine

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.data.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 引擎（frpc 可执行文件）定位与就绪检查。
 * 优先使用设置里指定的路径；否则使用安装包内置引擎（首次运行解压到 ~/.frpcdash/bin）。
 */
class EngineManager(private val settings: SettingsStore) {

    /**
     * 内置引擎版本，读一次就缓存。
     *
     * 为什么缓存：这个值在 `Main.kt` 的 `Sidebar(version = ...)` 与三处界面文案里都是
     * **每次重组就地读一遍** `/engine/VERSION`，而 `status` / `metrics` 两个 Flow
     * 每 2~4s 就会触发一次重组 —— 等于稳态也在反复开资源流。版本号进程内不会变，
     * 缓存没有任何副作用。
     */
    @Volatile
    private var bundledVersionCache: String? = null

    fun bundledVersion(): String {
        bundledVersionCache?.let { return it }
        val value = runCatching {
            javaClass.getResourceAsStream("/engine/VERSION")
                ?.bufferedReader()?.use { it.readText().trim() }
                .orEmpty()
        }.getOrDefault("").ifBlank { "unknown" }
        bundledVersionCache = value
        return value
    }

    /** 设置里指定且存在的自定义引擎优先，否则用内置解压出来的 */
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

    /** 确保引擎可用：自定义路径 → 已解压 → 从 resources 解压；都不行则返回失败原因 */
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

    /** 直接问引擎要版本（`frpc -v`），失败则回退到内置 VERSION 文件 */
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
