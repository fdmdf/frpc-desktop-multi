package com.frpc.shared.runtime

/**
 * frpc 子进程资源占用快照（数据来自 Linux /proc）。
 * 在非 Linux 桌面环境（如 Windows）下 read 返回 null。
 */
data class ProcessStats(
    val pid: Int,
    /** utime + stime 累计（jiffies，clock ticks） */
    val cpuJiffies: Long,
    /** 常驻内存 KB */
    val rssKb: Long,
    val threads: Int,
    /** 进程状态字符：R/S/D/Z 等 */
    val state: Char,
)

/** 读取 /proc/<pid> 的资源占用 */
class ProcessStatsReader(
    /** USER_HZ，Linux/Android 上为 100 */
    private val clockTicks: Long = 100,
) {
    fun read(pid: Int): ProcessStats? {
        val statFile = java.io.File("/proc/$pid/stat")
        val statmFile = java.io.File("/proc/$pid/statm")
        if (!statFile.exists() || !statmFile.exists()) return null
        return try {
            val statText = statFile.readText()
            // comm 字段可能含空格/括号，取最后一个 ')' 之后再按字段切分
            val tail = statText.substringAfterLast(')').trim().split(' ')
            if (tail.size < 18) return null
            val state = tail[0].firstOrNull() ?: '?'
            val utime = tail[11].toLong()   // 字段 14
            val stime = tail[12].toLong()   // 字段 15
            val threads = tail[17].toInt()  // 字段 20
            val residentPages = statmFile.readText().split(' ').getOrNull(1)?.toLong() ?: 0L
            val pageSizeKb = 4L // 4096 字节页，Linux/Android 通用
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

/**
 * CPU 占用率计算器：两次采样间 (jiffies 增量 / USER_HZ) / 墙钟增量。
 * 用法：每次拿到 ProcessStats 后调用 sample(stats, nowMs)。
 */
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
