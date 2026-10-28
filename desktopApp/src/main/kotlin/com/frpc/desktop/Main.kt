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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import com.frpc.desktop.core.UpdateChecker
import com.frpc.desktop.ui.AboutDialog
import com.frpc.desktop.ui.AppTheme
import com.frpc.desktop.ui.AppTray
import com.frpc.desktop.ui.HomeScreen
import com.frpc.desktop.ui.LogScreen
import com.frpc.desktop.ui.MonitorScreen
import com.frpc.desktop.ui.ProfilesScreen
import com.frpc.desktop.ui.SettingsScreen
import com.frpc.desktop.ui.TrayActions
import com.frpc.desktop.ui.TrayRunState
import com.frpc.desktop.ui.TrayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.net.URI
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

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

/** 从 classpath 读取与 Android 端同一份矢量渲染出的图标 */
private fun appIconPainter(): BitmapPainter? = runCatching {
    val bytes = AppContainer::class.java.getResourceAsStream("/icon.png")?.readBytes()
        ?: return@runCatching null
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

fun main(args: Array<String>) {
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
    if ("--tray-test" in args) {
        runTrayTest(args)
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
    app()
}

/** 第二个实例通过本地端口发来的「显示窗口」请求 */
object AppActivation {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun request() {
        requests.tryEmit(Unit)
    }
}

/**
 * 托盘自检：把托盘菜单**离屏渲染**成 PNG，用来核对
 * 中文有没有渲染成方框、图标/分隔线/勾选态是否正常、深浅色两套配色对不对。
 *
 * 不用 Robot 截屏的原因：菜单是重量级弹层（独立窗口），在无交互桌面 / CI 上只能拍到桌面底色。
 * `printAll()` 走的是同一套 Swing 绘制代码，字体、图标、配色、间距都能如实反映。
 *
 * 用法：Frpc-Android-Multi.exe --tray-test <输出png>
 * 产出 <输出png>（深色）与 <输出png 去掉扩展名>-light.png（浅色）两张图。
 */
private fun runTrayTest(args: Array<String>) {
    val target = File(args.getOrNull(args.indexOf("--tray-test") + 1) ?: "tray-test.png")
    if (!AppTray.install(
            TrayActions(
                onShowWindow = {}, onHideWindow = {}, onToggleRun = {}, onReload = {},
                onOpenStatusPage = {}, onOpenSettings = {}, onCheckUpdate = {}, onAbout = {},
                onQuit = {}, onAutoStartChange = {}, onCloseToTrayChange = {},
            ),
        )
    ) {
        println("tray not supported")
        exitProcess(4)
    }
    AppTray.updateTooltip("Frpc-Android-Multi · 运行中 · 代理 3/3 · 访问者 1/1")

    /** 按当前菜单状态离屏渲染一张；bg 模拟托盘所在的桌面底色 */
    fun renderMenu(bg: Color): BufferedImage {
        val popup = AppTray.popupForTest() ?: run { println("no popup"); exitProcess(5) }
        val pref = popup.preferredSize
        val w = if (pref.width > 20) pref.width else 320
        val h = if (pref.height > 20) pref.height else 400
        popup.setSize(w, h)
        popup.validate()
        popup.doLayout()
        val img = BufferedImage(w + 16, h + 16, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = bg
        g.fillRect(0, 0, img.width, img.height)
        g.translate(8, 8)
        popup.printAll(g)
        g.dispose()
        println("menu: items=${popup.componentCount} size=${popup.size}")
        return img
    }

    var darkShot: BufferedImage? = null
    var lightShot: BufferedImage? = null
    SwingUtilities.invokeAndWait {
        // 运行中 + 有配置 + 状态页可用：菜单项最全、禁用态最少的一条路径
        val full = TrayState(
            windowVisible = true,
            run = TrayRunState.RUNNING,
            canStart = true,
            statusPort = 7400,
            autoStart = true,
            closeToTray = false,
            dark = true,
        )
        AppTray.update(full)
        darkShot = renderMenu(Color(0x2B, 0x2B, 0x2B))
        AppTray.update(full.copy(dark = false))
        lightShot = renderMenu(Color(0xFA, 0xFA, 0xFA))
    }
    ImageIO.write(darkShot ?: run { exitProcess(6) }, "png", target)
    val lightFile = File(target.parentFile, target.nameWithoutExtension + "-light.png")
    ImageIO.write(lightShot ?: run { exitProcess(6) }, "png", lightFile)
    println("saved: ${target.absolutePath}")
    println("saved: ${lightFile.absolutePath}")
    exitProcess(0)
}

private fun app() = application {
    val container = remember { AppContainer().also { it.init() } }
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
    // 主窗口句柄：托盘菜单要把隐藏的窗口重新唤到前台
    var awtWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    // 更新检查的结果会就地替换托盘菜单里「检查更新」那一项的文案
    var checkUpdateJob by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    // 第二个实例启动时会把请求发过来：把窗口显示出来即可
    LaunchedEffect(Unit) {
        AppActivation.requests.collect {
            windowVisible = true
        }
    }

    fun quitApp() {
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
        checkUpdateJob = null
        uiScope.launch(Dispatchers.IO) {
            val result = UpdateChecker.check(AppInfo.version)
            checkingUpdate = false
            checkUpdateJob = result
            // 有新版就直接打开下载页：托盘气泡通知是系统绘制的，中文同样会变成方框
            if (result.state == UpdateChecker.State.AVAILABLE) {
                openBrowser(UpdateChecker.RELEASES_PAGE)
            }
        }
    }

    // 托盘菜单的深浅色：应用主题显式指定时用指定值，「跟随系统」时才去问系统
    val darkMenu = remember(settings.theme) {
        when (settings.theme) {
            "dark" -> true
            "light" -> false
            else -> OsTheme.prefersDark()
        }
    }

    // 启动时按设置（或截图自检参数）自动运行默认配置
    LaunchedEffect(Unit) {
        delay(500)
        if (System.getProperty("frpc.autostart") == "true") {
            val s = container.settings.state.value
            val profile = container.profiles.find(s.defaultProfileId)
                ?: container.profiles.profiles.value.firstOrNull()
            profile?.let { container.supervisor.start(it) }
            return@LaunchedEffect
        }
        if (container.settings.state.value.autoStartOnLaunch) {
            val s = container.settings.state.value
            val profile = container.profiles.find(s.defaultProfileId)
                ?: container.profiles.profiles.value.firstOrNull()
            profile?.let { container.supervisor.start(it) }
        }
    }

    // 托盘提示：鼠标悬停即可看到运行状态与在线数，不用切窗口
    val runState by container.supervisor.state.collectAsState()
    val proxyStatus by container.supervisor.status.collectAsState()
    val visitorsOnline by container.supervisor.visitors.collectAsState()
    val activeCounts by container.supervisor.activeCounts.collectAsState()
    val trayProfiles by container.profiles.profiles.collectAsState()
    val activeProfile = trayProfiles.firstOrNull { it.id == runState.profileId }
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

    // 系统托盘：AWT 实现（Compose 的 Tray 无法设菜单字体，中文会渲染成方框）
    LaunchedEffect(Unit) {
        AppTray.install(
            TrayActions(
                onShowWindow = {
                    windowVisible = true
                    awtWindow?.toFront()
                },
                onHideWindow = { windowVisible = false },
                onToggleRun = {
                    if (container.supervisor.state.value.phase == FrpcSupervisor.Phase.RUNNING) {
                        container.supervisor.stop()
                    } else {
                        val s = container.settings.state.value
                        val profile = container.profiles.find(s.defaultProfileId)
                            ?: container.profiles.profiles.value.firstOrNull()
                        profile?.let { container.supervisor.start(it) }
                    }
                },
                onReload = { container.supervisor.reload() },
                onOpenStatusPage = {
                    val port = container.supervisor.state.value.adminPort
                    if (port > 0) openBrowser("http://127.0.0.1:$port/static/")
                },
                onOpenSettings = {
                    tab = Tab.SETTINGS
                    windowVisible = true
                    awtWindow?.toFront()
                },
                onCheckUpdate = { runCheckUpdate() },
                onAbout = {
                    windowVisible = true
                    showAbout = true
                },
                onQuit = { quitApp() },
                onAutoStartChange = { v -> container.settings.update { it.copy(autoStartOnLaunch = v) } },
                onCloseToTrayChange = { v -> container.settings.update { it.copy(closeToTray = v) } },
            ),
        )
    }

    val trayRunState = when (runState.phase) {
        FrpcSupervisor.Phase.RUNNING -> TrayRunState.RUNNING
        FrpcSupervisor.Phase.STARTING -> TrayRunState.STARTING
        FrpcSupervisor.Phase.STOPPING -> TrayRunState.STOPPING
        else -> TrayRunState.IDLE
    }
    val updateLabel = when {
        checkingUpdate -> "正在检查更新…"
        checkUpdateJob?.state == UpdateChecker.State.AVAILABLE ->
            "有新版本 ${checkUpdateJob?.version}（点击查看）"
        checkUpdateJob?.state == UpdateChecker.State.UP_TO_DATE ->
            "已是最新 ${checkUpdateJob?.version}"
        checkUpdateJob?.state == UpdateChecker.State.FAILED -> "检查更新失败，点此重试"
        else -> "检查更新"
    }
    LaunchedEffect(trayTooltip) { AppTray.updateTooltip(trayTooltip) }
    LaunchedEffect(
        windowVisible, trayRunState, trayProfiles.size, runState.adminPort, runState.adminEnabled,
        settings.autoStartOnLaunch, settings.closeToTray, darkMenu, updateLabel, checkingUpdate,
    ) {
        AppTray.update(
            TrayState(
                windowVisible = windowVisible,
                run = trayRunState,
                canStart = trayProfiles.isNotEmpty(),
                statusPort = runState.adminPort.takeIf { runState.adminEnabled && it > 0 },
                autoStart = settings.autoStartOnLaunch,
                closeToTray = settings.closeToTray,
                dark = darkMenu,
                updateLabel = updateLabel,
                updateEnabled = !checkingUpdate,
            ),
        )
    }
    DisposableEffect(Unit) { onDispose { AppTray.remove() } }

    if (windowVisible) {
        Window(
            icon = appIconPainter(),
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
                awtWindow = window
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
                        version = container.engine.bundledVersion(),
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
