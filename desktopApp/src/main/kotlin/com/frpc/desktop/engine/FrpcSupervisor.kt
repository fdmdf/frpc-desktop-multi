package com.frpc.desktop.engine

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.data.ProfileStore
import com.frpc.desktop.core.StartupTrace
import com.frpc.desktop.data.SettingsStore
import com.frpc.desktop.runtime.ProcMetrics
import com.frpc.desktop.runtime.ProcessMetricsReader
import com.frpc.desktop.runtime.ProcessScanner
import com.frpc.shared.admin.FrpcAdminClient
import com.frpc.shared.admin.ProxyStatusSnapshot
import com.frpc.shared.logs.FrpcErrorHints
import com.frpc.shared.model.ServerProfile
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

    /**
     * frpc **当前真正加载的那份配置**里启用中的规则数（代理 / 访问者）。
     *
     * 界面列表来自「最新保存的配置」：禁一条但还没重载时，列表少一条、frpc 却还在跑它，
     * 直接按最新配置算分母就会出现「在线 3 / 总 2」这种分子大于分母的数字。
     * 这份快照只在 frpc 真正加载配置时更新（启动 / 重载成功 / 自动重启），
     * 拿它当分母才和「在线数」是同一套口径。
     */
    data class ActiveRuleCounts(
        val proxies: Int = 0,
        val visitors: Int = 0,
    )

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    /**
     * 已存盘但还没重载的配置 id。
     *
     * 运行中的配置允许直接编辑保存（旧做法是整页禁用，改个端口都得先停服务），
     * 但 frpc 手里还是旧配置 —— 这个标记让界面把「保存 ≠ 生效」说清楚。
     */
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

    /** 已注册的访问者名字（admin API 不返回 visitor，来自 "visitor added: [...]" 日志） */
    private val _visitors = MutableStateFlow<Set<String>>(emptySet())
    val visitors: StateFlow<Set<String>> = _visitors.asStateFlow()

    /** frpc 当前生效配置里启用中的规则数（口径见 [ActiveRuleCounts]） */
    private val _activeCounts = MutableStateFlow(ActiveRuleCounts())
    val activeCounts: StateFlow<ActiveRuleCounts> = _activeCounts.asStateFlow()

    private fun rememberActiveCounts(profile: ServerProfile) {
        _activeCounts.value = ActiveRuleCounts(
            proxies = profile.config.proxies.count { it.enabled != false },
            visitors = profile.config.visitors.count { it.enabled != false },
        )
    }

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

    /** 启停串行锁：避免「停止」收尾把紧接着的「启动」状态覆盖掉 */
    private val opMutex = Mutex()

    // ---------------------------------------------------------------- 对外操作

    fun start(profile: ServerProfile, isRestart: Boolean = false) {
        scope.launch {
            opMutex.withLock {
                // 已经在跑就先停掉，否则旧进程会变成孤儿继续占用端口
                if (process?.isAlive == true) stopInternal()
                startInternal(profile, isRestart)
            }
        }
    }

    fun stop() {
        scope.launch { opMutex.withLock { stopInternal() } }
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
        // 进程都停了，「待重载」自然失效
        clearPendingReload()
        _state.value = RunState(phase = Phase.IDLE)
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * 启动时清扫上次强杀留下的孤儿 frpc。
     * 任务管理器结束进程 / kill -9 不会执行 shutdown hook，frpc 会继续占着端口；
     * 判定标准是命令行里包含我们的 run 目录——只杀自己拉起来的，不碰别人的 frpc。
     *
     * **调用方负责判断要不要调**：绝大多数启动上次都是正常退出的（没有孤儿），
     * 而 Windows 上 fork powershell 的冷启动是 1.5~4s，每次开机都付这笔成本不划算。
     * 由 `AppContainer.cleanupOrphansIfNeeded()` 看 `CleanShutdownFlag` 决定，
     * 并且延后到首帧之后再发起（不与首屏抢 CPU / IO / 杀软扫描）。
     */
    fun cleanupOrphans() {
        scope.launch(Dispatchers.IO) {
            val killed = runCatching { killOrphans() }.getOrDefault(0)
            StartupTrace.mark("cleanupOrphans done killed=$killed")
            if (killed > 0) {
                appendSystemLog(LogLevel.INFO, "已清理上次残留的 $killed 个 frpc 进程")
            }
        }
    }

    /**
     * 优先用 [ProcessScanner]（纯 Java，零 fork）；
     * 拿不到命令行（受保护进程 / 老 JDK）时才回退到下面的 shell 实现 ——
     * **那套实现必须保留**，否则在不支持 `commandLine()` 的环境里就再也清不掉孤儿了。
     */
    private fun killOrphans(): Int {
        val token = AppPaths.runDir.absolutePath
        if (ProcessScanner.supportsCommandLine()) {
            val pids = runCatching { ProcessScanner.pidsByCommandLine(token) }.getOrDefault(emptyList())
            // 埋点：真机验收时靠这几条 mark 判断走的是零 fork 路径还是回退路径
            // （有的机器/JDK 读不到进程命令行，会 100% 走下面的 shell 分支）
            StartupTrace.mark("cleanupOrphans via ProcessHandle pids=${pids.size}")
            if (pids.isEmpty()) return 0
            val killed = runCatching { ProcessScanner.kill(pids) }.getOrDefault(0)
            StartupTrace.mark("cleanupOrphans ProcessHandle killed=$killed/${pids.size}")
            return killed
        }
        StartupTrace.mark("cleanupOrphans via shell (commandLine unreadable)")
        val killed = killOrphansByShell(token)
        StartupTrace.mark("cleanupOrphans shell killed=$killed")
        return killed
    }

    /** 回退路径：fork `powershell` / `pgrep`。慢（1.5~4s），但覆盖率最好 */
    private fun killOrphansByShell(token: String): Int {
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
                        // 重载成功 = frpc 现在跑的就是这份最新配置
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
            // toml 落盘的这一刻起 frpc 用的就是这份配置：分母以此为准
            rememberActiveCounts(profile)
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
        val adminPort = _state.value.adminPort
        // 停止后界面按「当前显示的配置」算数，这份快照不再有意义
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
        // 等端口被内核收回去再对外宣布「已停止」，
        // 否则停止后立刻启动会撞上上一次实例残留的监听/连接（见 PortCheck 注释）
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
                // frpc 输出 UTF-8；Windows 默认字符集是 GBK，不指定会乱码
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    appendLog(FrpcLogParser.parse(line))
                    // frp 报错太原始：追加一条人话说明，用户不用查文档
                    FrpcErrorHints.hintFor(line)?.let { hint ->
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
        if (webServer != null && webServer.port > 0 && PortCheck.isInUse(webServer.port)) {
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
