package com.frpc.shared.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 两端（Android / 桌面）共用的展示格式化。
 *
 * 为什么时区钉死在 Asia/Shanghai 而不是跟设备时区： frpc 的日志时间、以及用户截图里那份
 * 日志能否与别人对上，取决于「同一份日志在两端显示成同一个时间」。跟随设备时区会让同一条
 * 记录在不同人机器上差好几个小时，跨端排查时极易误判顺序。
 * （合并前：Android 已经是北京时间，桌面端用的是系统默认时区，这里是唯一的行为差异点。）
 *
 * 小数统一用 [Locale.US]：避免在某些语言环境下小数分隔符变成逗号，`1.5 MB` 显示成 `1,5 MB`。
 */
private val DISPLAY_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(DISPLAY_ZONE)
private val timeWithDate = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(DISPLAY_ZONE)
private val clockOnly = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(DISPLAY_ZONE)
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE)

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

/** 内存 KB → "12.3 MB" / "1.23 GB"；取不到或为 0 显示 `--` */
fun formatMemoryKb(kb: Long?): String {
    if (kb == null || kb <= 0) return "--"
    return when {
        kb < 1024 -> "$kb KB"
        kb < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", kb / 1024.0 / 1024.0)
    }
}

fun formatPercent(value: Float?): String =
    if (value == null) "--" else String.format(Locale.US, "%.1f%%", value)

/** 日志时间；非今天的记录附带日期。[nowMs] 可注入，便于测试与「按当前帧时钟」对齐 */
fun formatLogTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val time = Instant.ofEpochMilli(epochMs)
    val day = time.atZone(DISPLAY_ZONE).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(DISPLAY_ZONE).toLocalDate()
    return if (day == today) timeOnly.format(time) else timeWithDate.format(time)
}

/** 钟表时间 HH:mm:ss */
fun formatClock(epochMs: Long): String = clockOnly.format(Instant.ofEpochMilli(epochMs))

/** 日期时间 yyyy-MM-dd HH:mm:ss */
fun formatDateTime(epochMs: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(epochMs))
