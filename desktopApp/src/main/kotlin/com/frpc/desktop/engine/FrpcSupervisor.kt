package com.frpc.desktop.engine

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.data.ProfileStore
import com.frpc.desktop.data.ServerProfile
import com.frpc.desktop.data.SettingsStore
import com.frpc.desktop.runtime.ProcMetrics
import com.frpc.desktop.runtime.ProcessMetricsReader
import com.frpc.shared.admin.FrpcAdminClient
import com.frpc.shared.admin.ProxyStatusSnapshot
import com.frpc.shared.toml.FrpcToml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

/**
 * frpc 进程守护：启动/停止/热重载、日志采集、状态轮询、资源采样、崩溃自动重启。
 * 单实例（应用内同时只跑一个 frpc），与桌面客户端的使用模型一致。
 */
class FrpcSupervisor(
    private val scope: CoroutineScope,
    private val engineManager: EngineManager,
    private val profileStore: ProfileStore,
    private val settings: SettingsStore,
) {
    enum class Phase { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    data class RunState(
        val phase: Phase = Phase.IDLE,
        val profileId: String? = null,
        val profileName: String = "",
        val pid: Long? = null,
        val startedAtMs: Long? = null,
        val error: String? = null,
        val adminEnabled: Boolean = false,
        val adminPort: Int = 0,
        val restartCount: Int = 0,
    )

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _proxyStates = MutableStateFlow<Map<String, LogProxyState>>(emptyMap())
    val proxyStates: StateFlow<Map<String, LogProxyState>> = _proxyStates.asStateFlow()

    private val _status = MutableStateFlow<ProxyStatusSnapshot?>(null)
    val status: StateFlow<ProxyStatusSnapshot?> = _status.asStateFlow()

    /** 已注册的访问者名字（admin API 不返回 visitor，来自 "visitor added: [...]" 日志） */
    private val _visitors = MutableStateFlow<Set<String>>(emptySet())
    val visitors: StateFlow<Set<String>> = _visitors.asStateFlow()

    /**
     * 与 frps 的登录状态（P4）：null=未知（还没看到登录日志），
     * false=最近一次登录失败（token 错/网络不通），true=登录成功。
     * 进程活着 ≠ 连上了服务器，主页需要区分这两种"运行中"。
     */
    private val _serverConnected = MutableStateFlow<Boolean?>(null)
    val serverConnected: StateFlow<Boolean?> = _serverConnected.asStateFlow()

    /** 最近一次登录失败的描述，连接成功后清空 */
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _adminReachable = MutableStateFlow(false)
    val adminReachable: StateFlow<Boolean> = _adminReachable.asStateFlow()

    private val _metrics = MutableStateFlow<ProcMetrics?>(null)
    val metrics: StateFlow<ProcMetrics?> = _metrics.asStateFlow()

    private val logBuffer = ArrayDeque<LogLine>()
    private val metricsReader = ProcessMetricsReader()

    private var process: Process? = null
    private var adminClient: FrpcAdminClient? = null
    private var statusJob: Job? = null
    private var metricsJob: Job? = null
    private var watcherJob: Job? = null
    private var stopping = false

    // ---------------------------------------------------------------- 对外操作

    fun start(profile: ServerProfile, isRestart: Boolean = false) {
        scope.launch {
            // 已经在跑就先停掉，否则旧进程会变成孤儿继续占用端口
            if (process?.isAlive == true) stopInternal()
            startInternal(profile, isRestart)
        }
    }

    fun stop() {
        scope.launch { stopInternal() }
    }

    /**
     * 同步停止：给"应用退出"用。
     * JVM 退出不会自动回收子进程，必须在这里把 frpc.exe 杀掉，否则会留下孤儿进程占着端口。
     */
    fun stopBlocking() {
        stopping = true
        statusJob?.cancel(); statusJob = null
        metricsJob?.cancel(); metricsJob = null
        watcherJob?.cancel(); watcherJob = null
        val proc = process
        process = null
        adminClient = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        _metrics.value = null
        _status.value = null
        _adminReachable.value = false
        _serverConnected.value = null
        _loginError.value = null
        _state.value = RunState(phase = Phase.IDLE)
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * 启动时清扫上次强杀留下的孤儿 frpc。
     * 任务管理器结束进程 / kill -9 不会执行 shutdown hook，frpc 会继续占着端口；
     * 判定标准是命令行里包含我们的 run 目录——只杀自己拉起来的，不碰别人的 frpc。
     */
    fun cleanupOrphans() {
        scope.launch(Dispatchers.IO) {
            val killed = runCatching { killOrphans() }.getOrDefault(0)
            if (killed > 0) {
                appendSystemLog(LogLevel.INFO, "已清理上次残留的 $killed 个 frpc 进程")
            }
        }
    }

    private fun killOrphans(): Int {
        val token = AppPaths.runDir.absolutePath
        return if (AppPaths.isWindows) {
            val script = "\$mine='$token'\n" +
                "Get-CimInstance Win32_Process -Filter \"Name='frpc.exe'\" | " +
                "Where-Object { \$_.CommandLine -like \"*\$mine*\" } | " +
                "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue; \$_.ProcessId }"
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start()
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return 0
                }
                process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    .lineSequence().count { it.trim().toIntOrNull() != null }
            } finally {
                process.destroy()
            }
        } else {
            // pgrep 数一下个数，有再杀；pkill -f 的 pattern 是正则，路径里的点会多匹配但不会误伤（仍要求完整路径）
            val pgrep = ProcessBuilder("pgrep", "-f", token).start()
            val pids = pgrep.inputStream.bufferedReader().readText().lineSequence()
                .filter { it.trim().toIntOrNull() != null }.toList()
            if (pids.isEmpty()) return 0
            ProcessBuilder("pkill", "-f", token).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            pids.size
        }
    }

    /** 热重载：先写回最新配置，再走 admin API；端口变化或无 admin 时整进程重启 */
    fun reload() {
        scope.launch {
            val st = _state.value
            val profile = profileStore.find(st.profileId) ?: return@launch
            val client = adminClient
            val portChanged = (profile.config.webServer?.port ?: 0) != st.adminPort
            if (st.phase == Phase.RUNNING && client != null && !portChanged) {
                runCatching { writeToml(profile) }
                val ok = runCatching { client.reload() }.isSuccess
                appendSystemLog(
                    if (ok) LogLevel.INFO else LogLevel.ERROR,
                    if (ok) "已通过 admin API 重载配置" else "admin API 重载失败",
                )
            } else {
                stopInternal()
                startInternal(profile)
            }
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
            _logs.value = emptyList()
        }
    }

    fun logsSnapshot(): List<LogLine> = synchronized(logBuffer) { logBuffer.toList() }

    fun saveLogs(target: File): Result<File> = runCatching {
        target.writeText(
            logsSnapshot().joinToString("\n") {
                "${formatTime(it.timeMs)} [${it.level.name.first()}] ${it.message}"
            },
        )
        target
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun startInternal(profile: ServerProfile, isRestart: Boolean = false) {
        stopping = false
        metricsReader.reset()
        _proxyStates.value = emptyMap()
        _visitors.value = emptySet()
        _serverConnected.value = null
        _loginError.value = null
        _state.update {
            it.copy(
                phase = Phase.STARTING,
                error = null,
                profileId = profile.id,
                profileName = profile.name,
                pid = null,
                startedAtMs = null,
                restartCount = if (isRestart) it.restartCount else 0,
            )
        }

        val engine = engineManager.ensureReady().getOrElse { e ->
            val msg = e.message ?: e.toString()
            appendSystemLog(LogLevel.ERROR, "引擎不可用：$msg")
            _state.update { it.copy(phase = Phase.ERROR, error = msg) }
            return
        }

        // 启动前三态预检：把「点了启动才在日志里看到失败」提前成一句可读的原因
        checkBeforeStart(profile)?.let { msg ->
            appendSystemLog(LogLevel.ERROR, msg)
            _state.update { it.copy(phase = Phase.ERROR, error = msg) }
            return
        }

        try {
            val toml = writeToml(profile)
            appendSystemLog(LogLevel.INFO, "启动引擎：${engine.absolutePath} -c ${toml.absolutePath}")
            val builder = ProcessBuilder(engine.absolutePath, "-c", toml.absolutePath)
                .directory(AppPaths.runDir)
                .redirectErrorStream(true)
            // frpc 是 Go 程序，会无条件读取 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY：
            // 机器上一旦存在这些环境变量，连内网/本机 frps 也会被塞进代理，直接 502。
            // 默认清掉；需要走代理的用户请在配置里显式设置 transport.proxyURL。
            val env = builder.environment()
            env.keys.filter { it.lowercase() in PROXY_ENV_KEYS }.forEach { env.remove(it) }
            val started = builder.start()
            process = started

            val webServer = profile.config.webServer
            val adminEnabled = webServer != null && webServer.port > 0
            adminClient = if (adminEnabled && webServer != null) {
                FrpcAdminClient(
                    addr = webServer.addr,
                    port = webServer.port,
                    user = webServer.user,
                    password = webServer.password,
                )
            } else {
                null
            }

            _state.update {
                it.copy(
                    phase = Phase.RUNNING,
                    pid = started.pid(),
                    startedAtMs = System.currentTimeMillis(),
                    adminEnabled = adminEnabled,
                    adminPort = webServer?.port ?: 0,
                )
            }
            runCatching { profileStore.markUsed(profile.id) }

            // 立刻采一次作为基准，否则 CPU 要等第二个采样周期（约 4~6 秒）才有值
            runCatching { metricsReader.read(started.pid()) }

            readOutput(started)
            // 先起轮询再挂看护：startPollers 会重置轮询任务，顺序反了会把看护任务一起取消
            startPollers()
            watch(started, profile)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            appendSystemLog(LogLevel.ERROR, "启动失败：$msg")
            _state.update { it.copy(phase = Phase.ERROR, error = msg) }
            stopPollers()
        }
    }

    private suspend fun stopInternal() {
        stopping = true
        _state.update { it.copy(phase = Phase.STOPPING) }
        stopPollers()
        val proc = process
        if (proc != null) {
            withContext(Dispatchers.IO) {
                proc.destroy()
                if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        process = null
        adminClient = null
        metricsReader.reset()
        _metrics.value = null
        _status.value = null
        _adminReachable.value = false
        _proxyStates.value = emptyMap()
        _visitors.value = emptySet()
        _serverConnected.value = null
        _loginError.value = null
        _state.update {
            RunState(phase = Phase.IDLE, profileId = it.profileId, profileName = it.profileName)
        }
        appendSystemLog(LogLevel.INFO, "已停止 frpc")
    }

    private fun readOutput(proc: Process) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                // frpc 输出 UTF-8；Windows 默认字符集是 GBK，不指定会乱码
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    appendLog(FrpcLogParser.parse(line))
                    // frp 报错太原始：追加一条人话说明，用户不用查文档
                    com.frpc.shared.logs.FrpcErrorHints.hintFor(line)?.let { hint ->
                        appendSystemLog(LogLevel.WARN, hint)
                    }
                }
            }
        }
    }

    private fun watch(proc: Process, profile: ServerProfile) {
        watcherJob?.cancel()
        watcherJob = scope.launch(Dispatchers.IO) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (stopping) return@launch
            val current = _state.value
            if (current.phase == Phase.STOPPING || current.phase == Phase.IDLE) return@launch

            appendSystemLog(LogLevel.ERROR, "frpc 进程退出（exit=$code），准备自动重启")
            _state.update { it.copy(phase = Phase.ERROR, error = "进程退出（exit=$code）") }
            // 只停状态/资源轮询：stopPollers() 会把本协程自己也取消掉，导致 autoRestart 永远执行不到
            stopStatusPollers()
            watcherJob = null
            autoRestart(profile)
        }
    }

    private suspend fun autoRestart(profile: ServerProfile) {
        val attempt = _state.value.restartCount + 1
        if (attempt > MAX_RESTARTS) {
            _state.update {
                it.copy(error = "连续 $MAX_RESTARTS 次异常退出，已停止自动重启")
            }
            appendSystemLog(LogLevel.ERROR, "连续 $MAX_RESTARTS 次异常退出，停止自动重启")
            return
        }
        val waitMs = (2_000L shl (attempt - 1)).coerceAtMost(60_000L)
        appendSystemLog(LogLevel.WARN, "${waitMs / 1000} 秒后进行第 $attempt 次自动重启")
        _state.update { it.copy(restartCount = attempt) }
        delay(waitMs)
        if (stopping) return
        startInternal(profile, isRestart = true)
    }

    private fun startPollers() {
        stopStatusPollers()
        statusJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                pollStatus()
            }
        }
        metricsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(METRICS_INTERVAL_MS)
                pollMetrics()
            }
        }
    }

    /** 只停状态/资源轮询，不动看护任务（看护任务内调用它时不能自杀） */
    private fun stopStatusPollers() {
        statusJob?.cancel(); statusJob = null
        metricsJob?.cancel(); metricsJob = null
    }

    private fun stopPollers() {
        stopStatusPollers()
        watcherJob?.cancel(); watcherJob = null
    }

    private suspend fun pollStatus() {
        val st = _state.value
        if (!st.adminEnabled || st.phase != Phase.RUNNING) return
        val client = adminClient ?: return
        runCatching { client.status() }
            .onSuccess {
                _status.value = it
                _adminReachable.value = true
            }
            .onFailure { _adminReachable.value = false }
    }

    private suspend fun pollMetrics() {
        val st = _state.value
        val pid = st.pid ?: return
        if (st.phase != Phase.RUNNING) return
        _metrics.value = withContext(Dispatchers.IO) { metricsReader.read(pid) }
    }

    private fun writeToml(profile: ServerProfile): File {
        val file = AppPaths.runConfigFile
        file.parentFile?.mkdirs()
        file.writeText(FrpcToml.encode(profile.config))
        return file
    }

    /**
     * 启动前置检查，返回 null 表示可以启动。
     * 对齐参考项目的 B1001（没填地址）/ B1006（webServer 端口被占）两类拦截。
     * UI 层也可以在点「启动」时先调它，直接弹提示并引导到配置页，不必等 frpc 退出。
     */
    fun checkBeforeStart(profile: ServerProfile): String? {
        val config = profile.config
        if (config.serverAddr.isBlank()) {
            return "还没填服务器地址：先在「配置」里补上 frps 地址再启动"
        }
        val webServer = config.webServer
        if (webServer != null && webServer.port > 0 && com.frpc.shared.net.PortCheck.isInUse(webServer.port)) {
            return "状态接口端口 ${webServer.port} 已被占用：同机跑多份配置时，每份要用不同的 webServer 端口"
        }
        return null
    }

    private fun appendSystemLog(level: LogLevel, message: String) {
        appendLog(LogLine(System.currentTimeMillis(), level, "app", message))
    }

    private fun appendLog(line: LogLine) {
        // 状态接口每 4 秒被轮询一次会产生两条访问日志，默认隐藏
        if (!settings.state.value.showAccessLogs && FrpcLogParser.isAccessLog(line.message)) return
        applyProxyEvent(line)
        // 输出线程与 UI/协程线程都会写：ArrayDeque 非线程安全，必须加锁
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            _logs.value = logBuffer.toList()
        }
    }

    private fun applyProxyEvent(line: LogLine) {
        when (val event = ProxyLogParser.parse(line.message)) {
            is ProxyLogEvent.Update -> _proxyStates.update { it + (event.name to event.state) }
            is ProxyLogEvent.VisitorsRegistered -> _visitors.update { it + event.names }
            ProxyLogEvent.LoginSuccess -> {
                _serverConnected.value = true
                _loginError.value = null
            }
            is ProxyLogEvent.LoginFailed -> {
                _serverConnected.value = false
                _loginError.value = event.error
            }
            ProxyLogEvent.Reset -> {
                _proxyStates.value = emptyMap()
                _visitors.value = emptySet()
            }
            null -> Unit
        }
    }

    private fun formatTime(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))

    private companion object {
        const val MAX_LOG_LINES = 3000
        const val MAX_RESTARTS = 5
        const val STATUS_INTERVAL_MS = 4_000L
        const val METRICS_INTERVAL_MS = 2_000L

        /** 启动引擎时需要剥离的代理环境变量（大小写不敏感比较） */
        val PROXY_ENV_KEYS = setOf("http_proxy", "https_proxy", "all_proxy", "no_proxy")
    }
}
