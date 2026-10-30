package com.frpc.android.service

import com.frpc.android.FrpcApplication
import com.frpc.android.data.ConfigRepository
import com.frpc.android.data.ServerProfile
import com.frpc.shared.admin.FrpcAdminClient
import com.frpc.shared.admin.ProxyStatusSnapshot
import com.frpc.shared.net.PortCheck
import com.frpc.shared.runtime.CpuUsageTracker
import com.frpc.shared.runtime.ProcessStats
import com.frpc.shared.runtime.ProcessStatsReader
import com.frpc.shared.toml.FrpcToml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

object FrpcSupervisor {

    enum class Phase { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    data class RuntimeState(
        val phase: Phase = Phase.IDLE,
        val profileId: String? = null,
        val profileName: String = "",
        val pid: Int? = null,
        val startedAtMs: Long? = null,
        val error: String? = null,
        val adminEnabled: Boolean = false,
        val adminPort: Int = 0,
        val restartCount: Int = 0,
        val abi: String? = null,
    ) {
        val isActive: Boolean get() = phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.STOPPING
    }

    data class ActiveRuleCounts(
        val proxies: Int = 0,
        val visitors: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var appContext: FrpcApplication
    private lateinit var configRepository: ConfigRepository
    private lateinit var binaryManager: FrpcBinaryManager

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val maxLogs = 3000
    private val logBuffer = ArrayDeque<LogLine>()
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<ProxyStatusSnapshot?>(null)
    val status: StateFlow<ProxyStatusSnapshot?> = _status.asStateFlow()

    private val _logProxyStates = MutableStateFlow<Map<String, LogProxyState>>(emptyMap())
    val logProxyStates: StateFlow<Map<String, LogProxyState>> = _logProxyStates.asStateFlow()

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

    private val _pendingReloadProfileId = MutableStateFlow<String?>(null)
    val pendingReloadProfileId: StateFlow<String?> = _pendingReloadProfileId.asStateFlow()

    fun markPendingReload(profileId: String) {
        _pendingReloadProfileId.value = profileId
    }

    fun clearPendingReload() {
        _pendingReloadProfileId.value = null
    }

    private val _stats = MutableStateFlow<ProcessStats?>(null)
    val stats: StateFlow<ProcessStats?> = _stats.asStateFlow()

    private val _cpuPercent = MutableStateFlow<Float?>(null)
    val cpuPercent: StateFlow<Float?> = _cpuPercent.asStateFlow()

    private var process: Process? = null
    private var watchJob: Job? = null
    private var statusJob: Job? = null
    private var statsJob: Job? = null
    private var adminClient: FrpcAdminClient? = null

    private val statsReader = ProcessStatsReader()
    private val cpuTracker = CpuUsageTracker()

    @Volatile
    private var stopping = false

    private val opMutex = Mutex()

    private const val MAX_RESTARTS = 5
    private const val STATUS_INTERVAL_MS = 4000L
    private const val STATS_INTERVAL_MS = 2000L
    private val BACKOFF_MS = longArrayOf(2000, 5000, 10_000, 30_000, 60_000)

    fun attach(app: FrpcApplication) {
        appContext = app
        configRepository = app.container.configRepository
        binaryManager = app.container.binaryManager
    }

    enum class StartBlockReason {
        NO_SERVER_ADDR,
        ENGINE_MISSING,
        ADMIN_PORT_IN_USE,
    }

    fun checkBeforeStart(profile: ServerProfile): StartBlockReason? {
        if (profile.config.serverAddr.isBlank()) return StartBlockReason.NO_SERVER_ADDR
        if (!binaryManager.isReady()) return StartBlockReason.ENGINE_MISSING
        val adminPort = profile.config.webServer?.port ?: 0
        if (adminPort > 0 && PortCheck.isInUse(adminPort)) {
            return StartBlockReason.ADMIN_PORT_IN_USE
        }
        return null
    }

    fun requestStart(profile: ServerProfile) {
        scope.launch {
            opMutex.withLock {
                val current = _state.value
                if (current.isActive) {
                    if (current.profileId == profile.id && current.phase == Phase.RUNNING) return@withLock
                    stopInternal()
                }
                startInternal(profile)
            }
        }
    }

    fun requestStop() {
        scope.launch { opMutex.withLock { stopInternal() } }
    }

    fun requestReload() {
        scope.launch {
            opMutex.withLock {
                val st = _state.value
                val profile = configRepository.find(st.profileId) ?: return@withLock
                val client = adminClient
                val adminPortChanged = (profile.config.webServer?.port ?: 0) != st.adminPort
                if (st.phase == Phase.RUNNING && client != null && !adminPortChanged) {
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

    private suspend fun startInternal(profile: ServerProfile, isRestart: Boolean = false) {
        stopping = false
        cpuTracker.reset()
        _logProxyStates.value = emptyMap()
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
        appendSystemLog(LogLevel.INFO, "启动配置「${profile.name}」→ ${profile.serverSummary}")
        killOrphanFrpc()
        try {
            val binary = binaryManager.ensureInstalled()
            val tomlFile = writeToml(profile)
            rememberActiveCounts(profile)
            val builder = ProcessBuilder(binary.absolutePath, "-c", tomlFile.absolutePath)
                .directory(appContext.filesDir)
                .redirectErrorStream(true)
            val started = builder.start()
            process = started

            val webServer = profile.config.webServer
            val adminEnabled = webServer != null && webServer.port > 0
            _state.update {
                it.copy(
                    phase = Phase.RUNNING,
                    pid = findChildPid(),
                    startedAtMs = System.currentTimeMillis(),
                    adminEnabled = adminEnabled,
                    adminPort = webServer?.port ?: 0,
                    abi = binaryManager.deviceAbi(),
                )
            }
            runCatching { configRepository.markUsed(profile.id) }

            if (webServer != null && webServer.port > 0) {
                adminClient = FrpcAdminClient(
                    addr = webServer.addr,
                    port = webServer.port,
                    user = webServer.user,
                    password = webServer.password,
                )
            }
            collectOutput(started)
            watchProcess(started, profile)
            startPollers()
        } catch (e: Exception) {
            appendSystemLog(LogLevel.ERROR, "启动失败：${cleanErrorMessage(e)}")
            _state.update { it.copy(phase = Phase.ERROR, error = cleanErrorMessage(e)) }
            stopPollers()
        }
    }

    private fun cleanErrorMessage(e: Exception): String {
        val msg = e.message ?: e.toString()
        val idx = msg.lastIndexOf("): ")
        return if (msg.startsWith("Cannot run program") && idx > 0) {
            msg.substring(idx + 3)
        } else {
            msg
        }
    }

    private suspend fun stopInternal() {
        stopping = true
        val adminPort = _state.value.adminPort
        _activeCounts.value = ActiveRuleCounts()
        _state.update { it.copy(phase = Phase.STOPPING) }
        appendSystemLog(LogLevel.INFO, "正在停止 frpc…")
        runCatching { adminClient?.stop() }
        val current = process
        runCatching {
            current?.destroy()
            if (current != null && !current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroyForcibly()
                current.waitFor(2, TimeUnit.SECONDS)
            }
        }
        watchJob?.cancel()
        watchJob = null
        process = null
        adminClient = null
        stopPollers()
        if (adminPort > 0) {
            val freed = PortCheck.awaitFree(adminPort, timeoutMs = 3000)
            if (!freed) {
                appendSystemLog(
                    LogLevel.WARN,
                    "端口 $adminPort 暂时还没释放，可能有其他程序占用",
                )
            }
        }
        _state.value = RuntimeState(phase = Phase.IDLE, abi = binaryManager.deviceAbi())
        clearPendingReload()
        _status.value = null
        _stats.value = null
        _cpuPercent.value = null
        _adminReachable.value = false
        _logProxyStates.value = emptyMap()
        _visitors.value = emptySet()
        _serverConnected.value = null
        _loginError.value = null
        appendSystemLog(LogLevel.INFO, "frpc 已停止")
    }

    private fun watchProcess(proc: Process, profile: ServerProfile) {
        watchJob?.cancel()
        watchJob = scope.launch {
            val exitCode = runCatching { proc.waitFor() }.getOrNull()
            if (stopping) return@launch
            appendSystemLog(LogLevel.WARN, "frpc 进程意外退出 (exit=$exitCode)，准备重启")
            _status.value = null
            val restarts = _state.value.restartCount
            if (restarts < MAX_RESTARTS) {
                val waitMs = BACKOFF_MS[restarts.coerceAtMost(BACKOFF_MS.lastIndex)]
                _state.update { it.copy(phase = Phase.STARTING, restartCount = restarts + 1, pid = null) }
                delay(waitMs)
                startInternal(profile, isRestart = true)
            } else {
                _state.update { it.copy(phase = Phase.ERROR, error = "frpc 多次异常退出，已停止自动重启（连续 $MAX_RESTARTS 次）") }
                stopPollers()
            }
        }
    }

    private fun collectOutput(proc: Process) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    appendLog(FrpcLogParser.parse(line))
                    com.frpc.shared.logs.FrpcErrorHints.hintFor(line)?.let { hint ->
                        appendSystemLog(LogLevel.WARN, hint)
                    }
                }
            }
        }
    }

    private fun startPollers() {
        statusJob?.cancel()
        statsJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                pollStatus()
            }
        }
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                pollStats()
            }
        }
    }

    private fun stopPollers() {
        statusJob?.cancel()
        statsJob?.cancel()
        statusJob = null
        statsJob = null
    }

    private suspend fun pollStatus() {
        val st = _state.value
        if (!st.adminEnabled || st.phase != Phase.RUNNING) return
        val client = adminClient ?: return
        runCatching { client.status() }
            .onSuccess { snapshot ->
                _status.value = snapshot
                _adminReachable.value = true
            }
            .onFailure { _adminReachable.value = false }
    }

    private suspend fun pollStats() {
        val st = _state.value
        if (st.phase != Phase.RUNNING) return
        val pid = st.pid ?: return
        val stats = statsReader.read(pid)
        if (stats != null) {
            _stats.value = stats
            _cpuPercent.value = cpuTracker.sample(stats)
        } else {
            val found = findChildPid()
            if (found != null && found != pid) {
                _state.update { it.copy(pid = found) }
                cpuTracker.reset()
            }
        }
    }

    private fun killOrphanFrpc() {
        val myUid = android.os.Process.myUid()
        var killed = 0
        runCatching {
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val status = File(dir, "status").let { f ->
                    if (f.exists()) runCatching { f.readText() }.getOrNull() else null
                } ?: return@forEach
                val name = status.lineSequence().firstOrNull { it.startsWith("Name:") }
                    ?.substringAfter("Name:")?.trim() ?: return@forEach
                if (name != "frpc" && !name.startsWith("libfrpc")) return@forEach
                val uid = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
                    ?: return@forEach
                if (uid != myUid) return@forEach
                if (runCatching { android.os.Process.killProcess(pid) }.isSuccess) killed++
            }
        }
        if (killed > 0) {
            appendSystemLog(LogLevel.INFO, "已清理 $killed 个残留的 frpc 进程")
        }
    }

    private fun findChildPid(): Int? {        val myPid = android.os.Process.myPid()
        return runCatching {
            File("/proc").listFiles()
                ?.mapNotNull { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                    val statFile = File(dir, "stat")
                    if (!statFile.exists()) return@mapNotNull null
                    val text = runCatching { statFile.readText() }.getOrNull() ?: return@mapNotNull null
                    val comm = text.substringAfter('(').substringBefore(')')
                    val tail = text.substringAfterLast(')').trim().split(' ')
                    val ppid = tail.getOrNull(1)?.toIntOrNull()
                    val isFrpc = comm == "frpc" || comm.startsWith("libfrpc")
                    if (isFrpc && ppid == myPid) pid else null
                }
                ?.firstOrNull()
        }.getOrNull()
    }

    private fun writeToml(profile: ServerProfile): File {
        val dir = File(appContext.filesDir, "run").apply { mkdirs() }
        val file = File(dir, "frpc.toml")
        file.writeText(FrpcToml.encode(profile.config))
        return file
    }

    private fun appendSystemLog(level: LogLevel, message: String) {
        appendLog(LogLine(System.currentTimeMillis(), level, "app", message))
    }

    private fun appendLog(line: LogLine) {
        if (!showAccessLogs() && FrpcLogParser.isAccessLog(line.message)) return
        updateProxyStateFromLog(line)
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > maxLogs) logBuffer.removeFirst()
            _logs.value = logBuffer.toList()
        }
    }

    private fun showAccessLogs(): Boolean =
        runCatching { appContext.container.settingsRepository.showAccessLogs.value }.getOrDefault(false)

    private fun updateProxyStateFromLog(line: LogLine) {
        when (val event = ProxyLogParser.parse(line.message)) {
            is ProxyLogEvent.Update ->
                _logProxyStates.update { it + (event.name to event.state) }
            is ProxyLogEvent.VisitorsRegistered ->
                _visitors.update { it + event.names }
            ProxyLogEvent.LoginSuccess -> {
                _serverConnected.value = true
                _loginError.value = null
            }
            is ProxyLogEvent.LoginFailed -> {
                _serverConnected.value = false
                _loginError.value = event.error
            }
            ProxyLogEvent.Reset -> {
                _logProxyStates.value = emptyMap()
                _visitors.value = emptySet()
            }
            null -> Unit
        }
    }
}
