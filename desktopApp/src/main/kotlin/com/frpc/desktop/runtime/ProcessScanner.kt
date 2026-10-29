package com.frpc.desktop.runtime

import java.util.concurrent.TimeUnit

/**
 * 纯 Java 的进程扫描（扫孤儿 frpc 用）。
 *
 * 为什么不用 `powershell` / `pgrep`：Windows 上 fork 一次 `powershell -Command Get-CimInstance`
 * 的冷启动是 1.5~4s，而且启动那几秒正好和首屏抢 CPU、磁盘、杀软扫描。
 * JDK 9+ 的 [ProcessHandle] 能直接拿到进程命令行，**零 fork**。
 *
 * 兼容性：受保护的进程（系统进程、被杀软挡住的）上 `info().commandLine()` 可能抛异常
 * 或返回空，所以：
 * - 每个进程都 `runCatching` 包住，单个进程读不到不影响其它进程；
 * - [supportsCommandLine] 取不到任何命令行时返回 false，调用方**回退到原来的 shell 实现**
 *   （那套实现必须保留，不能删）。
 */
object ProcessScanner {

    /**
     * 进程表抽样上限：判定「能不能读到命令行」时最多读这么多条进程信息。
     *
     * 取 200 而不是几十：读一条 `info()` 只是读一次 /proc（Linux）或一次
     * `NtQueryInformationProcess`（Windows），几百次也就几毫秒 —— 而误判 false 的代价是
     * **fork 一次 powershell（1.5~4s）**。宁可多读几十个进程，也不要误判。
     */
    private const val PROBE_SAMPLE = 200

    @Volatile
    private var commandLineSupported: Boolean? = null

    /**
     * 这台机器上 `ProcessHandle.info().commandLine()` 是否真的可用。
     * 结果缓存：判定本身要遍历进程表，没必要每次都做。
     *
     * 判定的顺序很讲究，不能只在进程表里抽样：
     * `allProcesses()` 是 **pid 升序**，前几十个几乎都是 PID 很小的系统/服务进程，
     * 恰恰是最可能读不到命令行的那一批。只看它们会误判成 false，
     * 白白付一次 powershell 冷启动。所以先问「自己 + 自己拉起的子进程」——
     * 这两个一定在我们自己的权限范围内，读得到就说明这台机器是支持的。
     */
    fun supportsCommandLine(): Boolean {
        commandLineSupported?.let { return it }
        val supported = runCatching { detect() }.getOrDefault(false)
        commandLineSupported = supported
        return supported
    }

    private fun detect(): Boolean {
        // 1) 本进程：一定有权读自己。读到就直接成立（最快、也最不会误判）
        if (commandOf(ProcessHandle.current()) != null) return true
        // 2) 自己拉起的子进程：权限等级与本进程相同
        for (child in ProcessHandle.current().descendants()) {
            if (commandOf(child) != null) return true
        }
        // 3) 兜底：进程表抽样，命中一条就算支持
        var seen = 0
        for (handle in ProcessHandle.allProcesses()) {
            if (++seen > PROBE_SAMPLE) break
            if (commandOf(handle) != null) return true
        }
        return false
    }

    /**
     * 读一条命令行；读不到 / 抛异常（受保护进程、`NtQuery` 被杀软挡住）一律返回 null。
     * 返回 null **不代表失败**，只是这一个进程读不出来。
     */
    private fun commandOf(handle: ProcessHandle): String? =
        runCatching { handle.info().commandLine().orElse(null) }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 命令行里包含 [token] 的进程 pid 列表。
     * token 用**绝对路径**（例如 `~/.frpcdash/run`）—— frpc 是按
     * `<引擎> -c <runDir>/frpc.toml` 启动的，只有我们拉起来的进程才会带上它，
     * 不会误伤用户自己跑的 frpc。
     */
    fun pidsByCommandLine(token: String): List<Long> {
        if (token.isBlank()) return emptyList()
        val self = ProcessHandle.current().pid()
        val hits = mutableListOf<Long>()
        for (handle in ProcessHandle.allProcesses()) {
            val pid = handle.pid()
            if (pid == self) continue
            val cmd = commandOf(handle)
            if (cmd != null && cmd.contains(token)) hits += pid
        }
        return hits
    }

    /**
     * 强杀给定 pid，返回**真的杀掉**的个数。
     *
     * 必须看 `destroyForcibly()` 的返回值：它返回 false 表示没权限 / 进程已经没了，
     * 那种情况不能计入 —— 否则日志会打出「已清理上次残留的 N 个 frpc 进程」，
     * 而进程其实还活着并占着端口，用户照着日志排查只会越查越偏。
     */
    fun kill(pids: List<Long>): Int {
        var killed = 0
        for (pid in pids) {
            val handle = runCatching { ProcessHandle.of(pid).orElse(null) }.getOrNull() ?: continue
            if (!handle.isAlive) continue
            val ok = runCatching {
                if (!handle.destroyForcibly()) {
                    false
                } else {
                    // 给系统一点时间回收，否则紧接着的端口检查会误判"还被占着"
                    handle.onExit().get(2, TimeUnit.SECONDS)
                    true
                }
            }.getOrDefault(false)
            if (ok) killed++
        }
        return killed
    }
}
