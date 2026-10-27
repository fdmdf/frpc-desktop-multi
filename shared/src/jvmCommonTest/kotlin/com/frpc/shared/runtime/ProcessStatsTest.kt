package com.frpc.shared.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessStatsTest {

    @Test
    fun readSelf_onLinux() {
        // 本仓库的开发/构建机可能是 Windows（无 /proc），此时跳过；真机/CI(Linux) 上真实执行
        if (!File("/proc/self/stat").exists()) {
            println("skip ProcessStatsTest: /proc not available on this host")
            return
        }
        val reader = ProcessStatsReader()
        val pid = ProcessHandle.current().pid().toInt()
        val stats = reader.read(pid)
        assertNotNull(stats)
        assertTrue(stats.cpuJiffies >= 0)
        assertTrue(stats.rssKb > 0)
        assertTrue(stats.threads >= 1)
    }

    @Test
    fun readMissingPid_returnsNull() {
        val reader = ProcessStatsReader()
        assertNull(reader.read(Int.MAX_VALUE))
    }

    @Test
    fun cpuTracker_computesDelta() {
        val tracker = CpuUsageTracker()
        val first = ProcessStats(pid = 1, cpuJiffies = 100, rssKb = 1024, threads = 2, state = 'S')
        val second = ProcessStats(pid = 1, cpuJiffies = 250, rssKb = 1024, threads = 2, state = 'S')
        assertNull(tracker.sample(first, nowMs = 0), "first sample has no baseline")
        // 1.5s 内增加 150 jiffies = 150% (150jiffies/100Hz)/1.5s
        assertEquals(100f, tracker.sample(second, nowMs = 1500)!!, 0.01f)
        tracker.reset()
        assertNull(tracker.sample(first, nowMs = 10_000))
    }
}
