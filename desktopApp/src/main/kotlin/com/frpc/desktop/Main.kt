package com.frpc.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.frpc.desktop.engine.FrpcSupervisor
import com.frpc.desktop.core.AppInfo
import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.core.OsTheme
import com.frpc.desktop.core.SingleInstance
import com.frpc.desktop.core.StartupTrace
import com.frpc.desktop.core.UpdateChecker
import com.frpc.desktop.ui.AboutDialog
import com.frpc.desktop.ui.AppIcons
import com.frpc.desktop.ui.AppTheme
import com.frpc.desktop.ui.AppTray
import com.frpc.desktop.ui.HomeScreen
import com.frpc.desktop.ui.LogScreen
import com.frpc.desktop.ui.MonitorScreen
import com.frpc.desktop.ui.ProfilesScreen
import com.frpc.desktop.ui.SettingsScreen
import com.frpc.desktop.ui.TrayEvent
import com.frpc.desktop.ui.TrayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import java.net.URI

private fun screenshotMode(args: Array<String>) {
    val index = args.indexOf("--screenshot")
    System.setProperty("frpc.screenshot", args[index + 1])
    if ("--autostart" in args) System.setProperty("frpc.autostart", "true")
    val tabIndex = args.indexOf("--tab")
    if (tabIndex > 0 && tabIndex + 1 < args.size) {
        System.setProperty("frpc.tab", args[tabIndex + 1].uppercase())
    }
    app()
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("主页", Icons.Filled.Home),
    PROFILES("配置", Icons.Filled.Dashboard),
    LOGS("日志", Icons.Filled.Article),
    MONITOR("监控", Icons.Filled.MonitorHeart),
    SETTINGS("设置", Icons.Filled.Settings),
}

fun main(args: Array<String>) {
    StartupTrace.mark("main entered")
    if ("--smoke" in args) {
        runSmoke(args)
        return
    }
    if ("--export-toml" in args) {
        runExportToml(args)
        return
    }
    if ("--screenshot" in args) {
        screenshotMode(args)
        return
    }
    if ("--silent" in args) {
        // 开机自启时用：不弹窗，直接进托盘并（按设置）自动连接
        System.setProperty("frpc.silent", "true")
    }
    // 单实例：第二个进程只负责把已有窗口唤到前台，然后自己退出
    if (!SingleInstance.acquire(File(AppPaths.home, "app.lock")) { AppActivation.request() }) {
        return
    }
    StartupTrace.mark("single instance acquired")
    // 后台预热：托盘要用到的字体枚举与图标解码（Windows 上合计 350ms~1.6s）。
    // 不碰任何 AWT 组件，所以能和下面的 JVM / Compose 初始化重叠掉，
    // 而不是像以前那样全部压在 EDT 上和首帧抢时间。
    Thread({ runCatching { AppTray.warmUp() } }, "frpc-tray-warmup").apply { isDaemon = true }.start()
    app()
}

/** 第二个实例通过本地端口发来的「显示窗口」请求 */
object AppActivation {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun request() {
        requests.tryEmit(Unit)
    }
}

private fun app() = application {
    val container = remember { StartupTrace.measure("container created") { AppContainer().also { it.init() } } }
    val shotPath = System.getProperty("frpc.screenshot")
    val settings by container.settings.state.collectAsState()
    var tab by remember {
        mutableStateOf(
            System.getProperty("frpc.tab")?.let { name ->
                runCatching { Tab.valueOf(name) }.getOrNull()
            } ?: Tab.HOME,
        )
    }
    var pendingEditProfileId by remember { mutableStateOf<String?>(null) }
    // --silent：开机自启时不弹窗，直接驻留托盘
    var windowVisible by remember { mutableStateOf(System.getProperty("frpc.silent") != "true") }
    // 托盘菜单里的「关于」：Compose 的 Dialog 必须挂在窗口上，所以先显示主窗口再弹
    var showAbout by remember { mutableStateOf(false) }
    // 「把窗口提到前台」的自增令牌：值变化就让 Window 里那个 effect 重新执行，
    // 窗口是新建的还是早就存在的都生效，重复调用也不会多开一个窗口
    var raiseRequest by remember { mutableStateOf(0) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    /**
     * 统一的「显示主窗口」入口：幂等 —— 已经开着只是提到前台，隐藏着就把它建回来，
     * 重复调用不会多开出一个窗口（窗口不论新建还是已存在，都会被提到前台）。
     */
    fun showMainWindow() {
        windowVisible = true
        raiseRequest++
    }

    // 第二个实例启动时会把请求发过来：把窗口显示出来即可
    LaunchedEffect(Unit) {
        AppActivation.requests.collect { showMainWindow() }
    }

    var quitting by remember { mutableStateOf(false) }
    fun quitApp() {
        // 幂等：标题栏关闭、托盘「退出」、DisposableEffect 可能先后走到这里，只处理第一次
        if (quitting) return
        quitting = true
        container.shutdown()
        AppTray.remove()
        SingleInstance.release()
        exitApplication()
    }

    fun openBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
        }
    }

    fun runCheckUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        uiScope.launch(Dispatchers.IO) {
            val result = UpdateChecker.check(AppInfo.version)
            checkingUpdate = false
            // 有新版就直接打开下载页：托盘气泡通知是系统绘制的，中文同样会变成方框
            if (result.state == UpdateChecker.State.AVAILABLE) {
                openBrowser(UpdateChecker.RELEASES_PAGE)
            }
        }
    }

    // 托盘菜单的深浅色：应用主题显式指定时用指定值，「跟随系统」时才去问系统。
    //
    // 关键：**合成期绝不 fork 外部命令**。Windows 的探测要 `reg.exe`，150~600ms，
    // 而这里跑在 EDT 上、又排在 Window(...) 之前 —— 以前它会直接把窗口创建推迟掉。
    // 现在首帧先用缓存值（没探测出来就按浅色），探测结果回来后改这个 state，
    // 由下面那个 LaunchedEffect(darkMenu, ...) 推给托盘，用户基本无感。
    var darkMenu by remember(settings.theme) {
        mutableStateOf(
            when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> OsTheme.prefersDarkCached() ?: false
            },
        )
    }
    LaunchedEffect(settings.theme) {
        if (settings.theme == "dark" || settings.theme == "light") return@LaunchedEffect
        OsTheme.prefersDarkAsync(this) { dark -> darkMenu = dark }
    }

    // 启动时按设置（或截图自检参数）自动运行默认配置。
    //
    // 以前这里是固定 `delay(500)` 再取列表：`profiles.load()` 是异步的，
    // 配置多 / 磁盘慢时 500ms 到了列表还是空的 ⇒ 默认档案找不到 ⇒
    // **自动连接静默失败**（既不启动也不报错，用户只看到「开机自启了但没连上」）。
    // 现在先等加载完成（带超时兜底），再选档案 —— 保证要么连上，要么明确「没有可连的配置」。
    LaunchedEffect(Unit) {
        container.profiles.awaitLoaded()
        StartupTrace.mark("profiles loaded")
        val auto = System.getProperty("frpc.autostart") == "true" ||
            container.settings.state.value.autoStartOnLaunch
        if (!auto) return@LaunchedEffect
        val s = container.settings.state.value
        val profile = container.profiles.find(s.defaultProfileId)
            ?: container.profiles.profiles.value.firstOrNull()
        if (profile == null) {
            StartupTrace.mark("autoConnect: no profile")
            return@LaunchedEffect
        }
        StartupTrace.mark("autoConnect start")
        container.supervisor.start(profile)
    }

    // 托盘提示：鼠标悬停即可看到运行状态与在线数，不用切窗口
    val runState by container.supervisor.state.collectAsState()
    val proxyStatus by container.supervisor.status.collectAsState()
    val visitorsOnline by container.supervisor.visitors.collectAsState()
    val activeCounts by container.supervisor.activeCounts.collectAsState()
    val trayProfileList by container.profiles.profiles.collectAsState()
    val activeProfile = trayProfileList.firstOrNull { it.id == runState.profileId }
    val trayTooltip = buildString {
        append("Frpc-Android-Multi")
        when (runState.phase) {
            FrpcSupervisor.Phase.RUNNING -> {
                append(" · 运行中")
                // 分母与「在线数」同口径：用 frpc 真正加载的那份配置，
                // 否则禁用一条但没重载时，托盘会出现「在线 3 / 总 2」这种分子大于分母的数字
                val proxyTotal = activeCounts.proxies
                if (proxyTotal > 0) {
                    val online = proxyStatus?.all?.count { it.status == "running" } ?: 0
                    append(" · 代理 ").append(online).append('/').append(proxyTotal)
                }
                val visitorTotal = activeCounts.visitors
                if (visitorTotal > 0) {
                    // 运行中删掉当前档案时 activeProfile 会是 null，这里不能强解引用
                    val vOnline = activeProfile?.config?.visitors
                        ?.count { it.enabled != false && it.name in visitorsOnline } ?: 0
                    append(" · 访问者 ").append(vOnline).append('/').append(visitorTotal)
                }
            }
            FrpcSupervisor.Phase.STARTING -> append(" · 启动中")
            FrpcSupervisor.Phase.STOPPING -> append(" · 停止中")
            FrpcSupervisor.Phase.ERROR -> append(" · 异常")
            FrpcSupervisor.Phase.IDLE -> append(" · 未运行")
        }
    }

    /** 托盘事件的唯一分发点；界面状态的读写都在 Compose 侧，托盘只负责渲染 */
    fun handleTrayEvent(event: TrayEvent) {
        when (event) {
            TrayEvent.ShowWindow -> showMainWindow()

            TrayEvent.OpenSettings -> {
                tab = Tab.SETTINGS
                showMainWindow()
            }

            TrayEvent.About -> {
                showMainWindow()
                showAbout = true
            }

            TrayEvent.Quit -> quitApp()
        }
    }

    // 系统托盘：AWT + 自绘菜单实现（Compose 的 Tray 与 AWT 原生 PopupMenu 都没法设菜单字体，中文会画成方框）
    LaunchedEffect(Unit) {
        AppTray.install(::handleTrayEvent)
        StartupTrace.mark("tray installed")
        // --silent（开机自启）时没有主窗口，窗口里那个「首帧之后」的钩子不会跑，这里补一次。
        // 有窗口时交给窗口那边的 effect，那里才是真正的首帧之后（方法本身是幂等的）
        if (!windowVisible) container.cleanupOrphansIfNeeded()
    }
    // 状态只有这一个推送入口：深浅色 + 悬停提示一起走 AppTray.update
    LaunchedEffect(darkMenu, trayTooltip) {
        AppTray.update(TrayState(dark = darkMenu, tooltip = trayTooltip))
    }
    DisposableEffect(Unit) { onDispose { AppTray.remove() } }

    if (windowVisible) {
        Window(
            // 只解码一次并缓存：以前这里是每次重组都读一遍 512 PNG 的实参表达式，
            // 而 status(4s) / metrics(2s) 会让 app() 反复重组，等于稳态也在持续解码
            icon = remember { AppIcons.windowPainter() },
            onCloseRequest = {
                if (settings.closeToTray) {
                    windowVisible = false
                } else {
                    quitApp()
                }
            },
            title = "Frpc-Android-Multi",
            state = rememberWindowState(
                width = 1280.dp,
                height = 820.dp,
                position = WindowPosition(Alignment.Center),
            ),
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(1120, 720)
                // 标题栏 / 任务栏 / Alt-Tab 的图标清晰度：AWT 只有拿到**多尺寸列表**
                // 才会按场景挑最接近的原图，只给一张 512 就会被缩放（发虚）。
                // 这一步必须在 Window 内容里等 window 就绪后再设，不能靠 icon= 那个 Painter。
                val images = AppIcons.windowIconImages()
                if (images.isNotEmpty()) window.iconImages = images
                StartupTrace.mark("window ready")
                // 孤儿清扫延后到首帧之后：它跑在 IO 线程不阻塞 EDT，
                // 但 Windows 上要 fork powershell（1.5~4s），会和首屏抢 CPU / 磁盘 / 杀软扫描。
                // 而且上次正常退出时这一步直接跳过（见 CleanShutdownFlag）。
                container.cleanupOrphansIfNeeded()
            }
            // 「提到前台」：不论是刚建出来的窗口还是早就存在的窗口，令牌一变就执行
            LaunchedEffect(raiseRequest) {
                window.toFront()
                window.requestFocus()
            }
            // 自检截图：置顶窗口 → 等渲染 → 抓窗口区域 → 退出
            LaunchedEffect(Unit) {
                val target = shotPath ?: return@LaunchedEffect
                window.isAlwaysOnTop = true
                window.toFront()
                window.requestFocus()
                delay(if (System.getProperty("frpc.autostart") == "true") 11_000 else 2_000)
                runCatching {
                    val bounds = window.bounds
                    val image = java.awt.Robot().createScreenCapture(bounds)
                    javax.imageio.ImageIO.write(image, "png", java.io.File(target))
                }
                delay(300)
                container.shutdown()
                exitApplication()
            }
            AppTheme(themeMode = settings.theme) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Sidebar(
                        current = tab,
                        onSelect = { tab = it },
                        // 版本号进程内不变，记住一次即可（bundledVersion() 自己也有缓存，
                        // 这里再包一层是为了让每次重建 Sidebar 都复用同一个值）
                        version = remember { container.engine.bundledVersion() },
                    )
                    VerticalDivider()
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                        when (tab) {
                            Tab.HOME -> HomeScreen(
                                container = container,
                                onEditProfile = { id ->
                                    pendingEditProfileId = id
                                    tab = Tab.PROFILES
                                },
                                onOpenStatusPage = { port ->
                                    runCatching {
                                        if (Desktop.isDesktopSupported()) {
                                            Desktop.getDesktop().browse(URI("http://127.0.0.1:$port/static/"))
                                        }
                                    }
                                },
                            )
                            Tab.PROFILES -> ProfilesScreen(
                                container = container,
                                openProfileId = pendingEditProfileId,
                                onOpenHandled = { pendingEditProfileId = null },
                            )
                            Tab.LOGS -> LogScreen(container)
                            Tab.MONITOR -> MonitorScreen(container)
                            Tab.SETTINGS -> SettingsScreen(container)
                        }
                    }
                }
                if (showAbout) {
                    AboutDialog(container) { showAbout = false }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    current: Tab,
    onSelect: (Tab) -> Unit,
    version: String,
) {
    Column(
        modifier = Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "Frpc-Android-Multi",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        Tab.entries.forEach { entry ->
            val selected = entry == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "引擎 frp $version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
