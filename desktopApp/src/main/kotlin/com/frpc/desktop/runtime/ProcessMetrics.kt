package com.frpc.desktop.runtime

import com.frpc.desktop.core.AppPaths
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.TimeUnit

data class ProcMetrics(
    val pid: Long,
    /** 相对整机 CPU（与任务管理器一致，0-100） */
    val cpuPercent: Float?,
    val rssKb: Long?,
    val threads: Int?,
    /** 进程状态字符（Windows 用 Run/Unknown 等文字） */
    val state: String?,
)

/**
 * 读取 frpc 子进程的资源占用。
 * - Linux/macOS 有 /proc 时直接读（快、无外部进程）
 * - Windows 走 `Get-CimInstance Win32_Process`（一次 PowerShell 调用，结果缓存 1.5s）
 * 读不到时返回 cpuPercent/rssKb 为 null，界面显示 `--`，不影响其他功能。
 */
class ProcessMetricsReader {

    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var cached: ProcMetrics? = null
    private var cachedAt = 0L
    private var lastCpu100ns: Long? = null
    private var lastCpuAt = 0L

    @Synchronized
    fun read(pid: Long): ProcMetrics? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < MIN_INTERVAL_MS) return cached
        val raw = if (AppPaths.isWindows) readWindows(pid) else readProc(pid)
        cached = raw?.let { sample ->
            val prev = lastCpu100ns
            val elapsedMs = now - lastCpuAt
            val cpu = if (prev != null && elapsedMs > 0 && sample.cpu100ns != null) {
                val deltaMs = (sample.cpu100ns - prev) / 10_000.0
                (deltaMs / (elapsedMs * cores) * 100.0).toFloat().coerceIn(0f, 100f)
            } else {
                null
            }
            if (sample.cpu100ns != null) {
                lastCpu100ns = sample.cpu100ns
                lastCpuAt = now
            }
            ProcMetrics(pid, cpu, sample.rssKb, sample.threads, sample.state)
        }
        cachedAt = now
        return cached
    }

    fun reset() {
        cached = null
        cachedAt = 0
        lastCpu100ns = null
        lastCpuAt = 0
    }

    private data class Raw(
        val cpu100ns: Long?,
        val rssKb: Long?,
        val threads: Int?,
        val state: String?,
    )

    // ---------------------------------------------------------------- Linux

    private fun readProc(pid: Long): Raw? {
        val statFile = File("/proc/$pid/stat")
        val statmFile = File("/proc/$pid/statm")
        if (!statFile.exists()) return null
        return runCatching {
            val text = statFile.readText()
            val tail = text.substringAfterLast(')').trim().split(' ')
            if (tail.size < 18) return null
            val state = tail[0]
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            val threads = tail[17].toInt()
            val pages = runCatching { statmFile.readText().split(' ')[1].toLong() }.getOrDefault(0L)
            Raw(
                // jiffies(1/100s) → 100ns：1 jiffy = 10ms = 10ms × 10000
                cpu100ns = (utime + stime) * 100_000,
                rssKb = pages * 4,
                threads = threads,
                state = state,
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- Windows

    private fun readWindows(pid: Long): Raw? {
        val script = "(Get-CimInstance Win32_Process -Filter \"ProcessId=$pid\" | " +
            "Select-Object KernelModeTime,UserModeTime,WorkingSetSize,ThreadCount | ConvertTo-Json -Compress)"
        val output = runCatching {
            val process = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script,
            ).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(4, TimeUnit.SECONDS)) {
                    // powershell 卡住时必须杀掉，否则每轮采样泄漏一个进程
                    process.destroyForcibly()
                    return@runCatching null
                }
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } finally {
                // 正常路径 powershell 已自行退出，这里只是幂等兜底
                process.destroy()
            }
        }.getOrNull()
        if (output.isNullOrBlank() || !output.startsWith("{")) return null
        return runCatching {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(output) as kotlinx.serialization.json.JsonObject
            fun field(name: String): Long? =
                (obj[name] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull?.toLongOrNull()
            val kernel = field("KernelModeTime") ?: 0L
            val user = field("UserModeTime") ?: 0L
            val workingSet = field("WorkingSetSize")
            Raw(
                cpu100ns = kernel + user,
                rssKb = workingSet?.let { it / 1024 },
                threads = field("ThreadCount")?.toInt(),
                state = null,
            )
        }.getOrNull()
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1_500L
    }
}
