package com.frpc.desktop.core

import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 启动耗时埋点。
 *
 * 为什么需要：冷启动各阶段的耗时在真机上差异极大（系统字体枚举 300ms~1.5s、
 * powershell 冷启动 1.5~4s、`reg.exe` 150~600ms），靠经验估值排优先级很容易排错，
 * 也可能把时间花在根本不慢的地方。这里给每个阶段打一个点，
 * 用 `-Dfrpc.startupTrace=true` 启动就能拿到一份带毫秒偏移的清单。
 *
 * 约定：
 * - `t0` 取**JVM 启动时刻**（`RuntimeMXBean.startTime`），这样连 JVM / Compose 的
 *   固有成本也能量出来，而不只是「进 main 之后」的耗时；
 * - 里程碑名一律 ASCII：Windows 控制台默认 GBK，中文日志会乱码，反而看不出问题；
 * - 不加那个开关时只在内存留一份（[report] 可随时取），不打任何日志。
 */
object StartupTrace {

    private const val FLAG = "frpc.startupTrace"

    /** 是否打印到 stdout：`-Dfrpc.startupTrace=true` */
    val enabled: Boolean = java.lang.Boolean.getBoolean(FLAG)

    /** JVM 启动时刻；所有偏移都相对它 */
    private val t0Ms: Long = ManagementFactory.getRuntimeMXBean().startTime

    private data class Mark(val name: String, val atMs: Long)

    private val marks = CopyOnWriteArrayList<Mark>()

    /** 打一个「到了这一阶段」的点 */
    fun mark(name: String) {
        val at = System.currentTimeMillis() - t0Ms
        marks.add(Mark(name, at))
        if (enabled) println("[startup] +${at}ms  $name")
    }

    /** 打一个耗时区间：`span("fonts", startedAt)` 会记一条 "fonts (123ms)" */
    fun span(name: String, startedAtMs: Long) {
        val cost = System.currentTimeMillis() - startedAtMs
        val at = System.currentTimeMillis() - t0Ms
        marks.add(Mark("$name (${cost}ms)", at))
        if (enabled) println("[startup] +${at}ms  $name took ${cost}ms")
    }

    /** 顺手记一段代码块的耗时，返回 block 的结果 */
    inline fun <T> measure(name: String, block: () -> T): T {
        val started = System.currentTimeMillis()
        return try {
            block()
        } finally {
            span(name, started)
        }
    }

    /** 全部里程碑（名字, 相对 JVM 启动的毫秒偏移） */
    fun snapshot(): List<Pair<String, Long>> = marks.map { it.name to it.atMs }

    /** 给日志页 / 手动排查用的一份文本 */
    fun report(): String = buildString {
        appendLine("startup trace (offset from JVM start):")
        marks.forEach { appendLine("  +${it.atMs}ms  ${it.name}") }
    }
}
