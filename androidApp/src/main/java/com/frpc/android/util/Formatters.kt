package com.frpc.android.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 全 App 展示统一使用北京时间（UTC+8），不受设备时区影响 */
val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(BEIJING_ZONE)
private val timeWithDate = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(BEIJING_ZONE)
private val clockOnly = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(BEIJING_ZONE)
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING_ZONE)

/** 运行时长：1d 02:03:04 / 02:03:04 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (days > 0) {
        String.format(Locale.US, "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

/** 内存 KB → "12.3 MB" / "1.2 GB" */
fun formatMemoryKb(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", kb / 1024.0 / 1024.0)
}

fun formatPercent(value: Float?): String =
    if (value == null) "--" else String.format(Locale.US, "%.1f%%", value)

/** 日志时间（北京时间）；非今天的记录附带日期 */
fun formatLogTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val time = Instant.ofEpochMilli(epochMs)
    val day = time.atZone(BEIJING_ZONE).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(BEIJING_ZONE).toLocalDate()
    return if (day == today) timeOnly.format(time) else timeWithDate.format(time)
}

/** 北京时间钟表时间 HH:mm:ss */
fun formatClock(epochMs: Long): String = clockOnly.format(Instant.ofEpochMilli(epochMs))

/** 北京时间日期时间 yyyy-MM-dd HH:mm:ss */
fun formatDateTime(epochMs: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(epochMs))

/** 本地端口占用检测（真实绑定尝试，用于启动前校验） */
fun isPortInUse(port: Int): Boolean = try {
    java.net.ServerSocket(port).use { false }
} catch (e: Exception) {
    true
}
