package com.frpc.desktop.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ZONE: ZoneId = ZoneId.systemDefault()
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

fun formatLogTime(epochMs: Long): String {
    val dateTime = Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDateTime()
    val today = java.time.LocalDate.now(ZONE)
    return if (dateTime.toLocalDate() == today) {
        dateTime.format(TIME)
    } else {
        dateTime.format(DATE_SHORT)
    }
}

fun formatClock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZONE).format(CLOCK)

fun formatDateTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZONE).format(DATE_TIME)

/** 毫秒时长 → 00:12:34 / 2d 03:04:05 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--"
    val totalSeconds = ms / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    val hms = "%02d:%02d:%02d".format(hours, minutes, seconds)
    return if (days > 0) "${days}d $hms" else hms
}

fun formatMemoryKb(kb: Long?): String {
    if (kb == null || kb <= 0) return "--"
    return if (kb < 1024) "$kb KB" else "%.1f MB".format(kb / 1024.0)
}

fun formatPercent(value: Float?): String =
    if (value == null) "--" else "%.1f%%".format(value)
