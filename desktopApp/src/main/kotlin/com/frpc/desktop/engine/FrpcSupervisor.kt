package com.frpc.desktop.engine

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.data.ProfileStore
import com.frpc.desktop.data.ServerProfile
import com.frpc.desktop.data.SettingsStore
import com.frpc.desktop.runtime.ProcMetrics
import com.frpc.desktop.runtime.ProcessMetricsReader
import com.frpc.shared.admin.FrpcAdminClient
import com.frpc.shared.admin.ProxyStatusSnapshot
import com.frpc.shared.net.PortCheck
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

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

    data class ActiveRuleCounts(
        val proxies: Int = 0,
        val visitors: Int = 0,
    )

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _pendingReloadProfileId = MutableStateFlow<String?>(null)
    val pendingReloadProfileId: StateFlow<String?> = _pendingReloadProfileId.asStateFlow()

    fun markPendingReload(profileId: String) {
        _pendingReloadProfileId.value = profileId
    }

    fun clearPendingReload() {
        _pendingReloadProfileId.value = null
    }

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _proxyStates = MutableStateFlow<Map<String, LogProxyState>>(emptyMap())
    val proxyStates: StateFlow<Map<String, LogProxyState>> = _proxyStates.asStateFlow()

    private val _status = MutableStateFlow<ProxyStatusSnapshot?>(null)
    val status: StateFlow<ProxyStatusSnapshot?> = _status.asStateFlow()

    private val _visitors = MutableStateFlow<Set<String>>(emptySet())
    val visitors: StateFlow<Set<String>> = _visitors.asStateFlow()

    private val _activeCounts = MutableStateFlow(ActiveRuleCounts())
    val activeCounts: StateFlow<ActiveRuleCounts> = _activeCounts.asStateFlow()

    private fun rememberActiveCounts(profile: ServerProfile) {
        _activeCounts.value = ActiveRuleCounts(
            proxies = profile.config.proxies.count { it.enabled != false },
            visitors = profile.config.visitors.count { it.enabled != false },
        )
    }

    private val _serverConnected = MutableStateFlow<Boolean?>(null)
    val serverConnected: StateFlow<Boolean?> = _serverConnected.asStateFlow()

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

    private val opMutex = Mutex()

    fun start(profile: ServerProfile, isRestart: Boolean = false) {
        scope.launch {
            opMutex.withLock {
                if (process?.isAlive == true) stopInternal()
                startInternal(profile, isRestart)
            }
        }
    }

    fun stop() {
        scope.launch { opMutex.withLock { stopInternal() } }
    }

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
        clearPendingReload()
        _state.value = RunState(phase = Phase.IDLE)
    }

    val isRunning: Boolean get() = process?.isAlive == true

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
            val pgrep = ProcessBuilder("pgrep", "-f", token).start()
            val pids = pgrep.inputStream.bufferedReader().readText().lineSequence()
                .filter { it.trim().toIntOrNull() != null }.toList()
            if (pids.isEmpty()) return 0
            ProcessBuilder("pkill", "-f", token).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            pids.size
        }
    }

    fun reload() {
        scope.launch {
            opMutex.withLock {
                val st = _state.value
                val profile = profileStore.find(st.profileId) ?: return@withLock
                val client = adminClient
                val portChanged = (profile.config.webServer?.port ?: 0) != st.adminPort
                if (st.phase == Phase.RUNNING && client != null && !portChanged) {
                    runCatching { writeToml(profile) }
                    val ok = runCatching { client.reload() }.isSuccess
                    appendSystemLog(
                        if (ok) LogLevel.INFO else LogLevel.ERROR,
                        if (ok) "已通过 admin API 重载配置" else "admin API 重载失败",
                    )
                    if (ok) {
                        rememberActiveCounts(profile)
                        clearPendingReload()
                    }
                } else {
                    stopInternal()
                    startInternal(profile)
                    clearPendingReload()
                }
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

        checkBeforeStart(profile)?.let { msg ->
            appendSystemLog(LogLevel.ERROR, msg)
            _state.update { it.copy(phase = Phase.ERROR, error = msg) }
            return
        }

        try {
            val toml = writeToml(profile)
            rememberActiveCounts(profile)
            appendSystemLog(LogLevel.INFO, "启动引擎：${engine.absolutePath} -c ${toml.absolutePath}")
            val builder = ProcessBuilder(engine.absolutePath, "-c", toml.absolutePath)
                .directory(AppPaths.runDir)
                .redirectErrorStream(true)
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

            runCatching { metricsReader.read(started.pid()) }

            readOutput(started)
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
        val adminPort = _state.value.adminPort
        _activeCounts.value = ActiveRuleCounts()
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
        if (adminPort > 0) {
            val freed = PortCheck.awaitFree(adminPort, timeoutMs = 3000)
            if (!freed) {
                appendSystemLog(LogLevel.WARN, "端口 $adminPort 暂时还没释放，可能有其他程序占用")
            }
        }
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
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    appendLog(FrpcLogParser.parse(line))
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
        if (!settings.state.value.showAccessLogs && FrpcLogParser.isAccessLog(line.message)) return
        applyProxyEvent(line)
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

        val PROXY_ENV_KEYS = setOf("http_proxy", "https_proxy", "all_proxy", "no_proxy")
    }
}
