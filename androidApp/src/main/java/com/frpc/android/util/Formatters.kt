package com.frpc.android.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(BEIJING_ZONE)
private val timeWithDate = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(BEIJING_ZONE)
private val clockOnly = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(BEIJING_ZONE)
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING_ZONE)

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

fun formatMemoryKb(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", kb / 1024.0 / 1024.0)
}

fun formatPercent(value: Float?): String =
    if (value == null) "--" else String.format(Locale.US, "%.1f%%", value)

fun formatLogTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val time = Instant.ofEpochMilli(epochMs)
    val day = time.atZone(BEIJING_ZONE).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(BEIJING_ZONE).toLocalDate()
    return if (day == today) timeOnly.format(time) else timeWithDate.format(time)
}

fun formatClock(epochMs: Long): String = clockOnly.format(Instant.ofEpochMilli(epochMs))

fun formatDateTime(epochMs: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(epochMs))

fun isPortInUse(port: Int): Boolean = try {
    java.net.ServerSocket(port).use { false }
} catch (e: Exception) {
    true
}
