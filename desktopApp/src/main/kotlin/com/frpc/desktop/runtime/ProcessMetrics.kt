package com.frpc.desktop.runtime

import com.frpc.desktop.core.AppPaths
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.TimeUnit

data class ProcMetrics(
    val pid: Long,
    val cpuPercent: Float?,
    val rssKb: Long?,
    val threads: Int?,
    val state: String?,
)

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
                cpu100ns = (utime + stime) * 100_000,
                rssKb = pages * 4,
                threads = threads,
                state = state,
            )
        }.getOrNull()
    }

    private fun readWindows(pid: Long): Raw? {
        val script = "(Get-CimInstance Win32_Process -Filter \"ProcessId=$pid\" | " +
            "Select-Object KernelModeTime,UserModeTime,WorkingSetSize,ThreadCount | ConvertTo-Json -Compress)"
        val output = runCatching {
            val process = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script,
            ).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(4, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } finally {
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
