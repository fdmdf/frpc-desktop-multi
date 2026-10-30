package com.frpc.shared.runtime

data class ProcessStats(
    val pid: Int,
    val cpuJiffies: Long,
    val rssKb: Long,
    val threads: Int,
    val state: Char,
)

class ProcessStatsReader(
    private val clockTicks: Long = 100,
) {
    fun read(pid: Int): ProcessStats? {
        val statFile = java.io.File("/proc/$pid/stat")
        val statmFile = java.io.File("/proc/$pid/statm")
        if (!statFile.exists() || !statmFile.exists()) return null
        return try {
            val statText = statFile.readText()
            val tail = statText.substringAfterLast(')').trim().split(' ')
            if (tail.size < 18) return null
            val state = tail[0].firstOrNull() ?: '?'
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            val threads = tail[17].toInt()
            val residentPages = statmFile.readText().split(' ').getOrNull(1)?.toLong() ?: 0L
            val pageSizeKb = 4L
            ProcessStats(
                pid = pid,
                cpuJiffies = utime + stime,
                rssKb = residentPages * pageSizeKb,
                threads = threads,
                state = state,
            )
        } catch (e: Exception) {
            null
        }
    }
}

class CpuUsageTracker(private val clockTicks: Long = 100) {
    private var lastJiffies: Long = -1
    private var lastTimeMs: Long = -1

    fun sample(stats: ProcessStats, nowMs: Long = System.currentTimeMillis()): Float? {
        val prevJiffies = lastJiffies
        val prevTime = lastTimeMs
        lastJiffies = stats.cpuJiffies
        lastTimeMs = nowMs
        if (prevJiffies < 0 || prevTime < 0 || nowMs <= prevTime) return null
        val jiffiesDelta = stats.cpuJiffies - prevJiffies
        if (jiffiesDelta < 0) return null
        return ((jiffiesDelta.toDouble() / clockTicks) / ((nowMs - prevTime) / 1000.0) * 100.0)
            .toFloat()
    }

    fun reset() {
        lastJiffies = -1
        lastTimeMs = -1
    }
}
