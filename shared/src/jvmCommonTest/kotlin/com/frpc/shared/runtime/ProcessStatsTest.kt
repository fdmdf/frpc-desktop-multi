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
        assertEquals(100f, tracker.sample(second, nowMs = 1500)!!, 0.01f)
        tracker.reset()
        assertNull(tracker.sample(first, nowMs = 10_000))
    }
}
