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
import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.core.SingleInstance
import com.frpc.desktop.core.StartupTrace
import com.frpc.desktop.ui.AppTheme
import com.frpc.desktop.ui.AppTray
import com.frpc.desktop.ui.HomeScreen
import com.frpc.desktop.ui.LogScreen
import com.frpc.desktop.ui.MonitorScreen
import com.frpc.desktop.ui.ProfilesScreen
import com.frpc.desktop.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
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

private fun appIconPainter(): BitmapPainter? = runCatching {
    val bytes = AppContainer::class.java.getResourceAsStream("/icon.png")?.readBytes()
        ?: return@runCatching null
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

fun main(args: Array<String>) {
    System.setProperty("compose.application.configure.swing.globals", "false")
    StartupTrace.mark("main-enter")
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        System.setProperty("apple.awt.application.appearance", "system")
    }
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
        System.setProperty("frpc.silent", "true")
    }
    if (!SingleInstance.acquire(File(AppPaths.home, "app.lock")) { AppActivation.request() }) {
        return
    }
    StartupTrace.mark("lock-acquired")
    app()
}

object AppActivation {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun request() {
        requests.tryEmit(Unit)
    }
}

private fun runTrayTest(args: Array<String>) {
    val target = File(args.getOrNull(args.indexOf("--tray-test") + 1) ?: "tray-test.png")
    if (!AppTray.install(onShow = {}, onStop = {}, onQuit = {})) {
        println("tray not supported")
        exitProcess(4)
    }
    AppTray.updateTooltip("Frpc-Android-Multi · 运行中 · 代理 3/3 · 访问者 1/1")
    AppTray.updateRunning(true)
    val popup = AppTray.popupForTest() ?: run { println("no popup"); exitProcess(5) }
    AppTray.showMenuAt(60, 60)
    Thread.sleep(1_200)
    val shot = Robot().createScreenCapture(Rectangle(55, 55, 240, 170))
    ImageIO.write(shot, "png", target)
    println("saved: ${target.absolutePath}")
    exitProcess(0)
}

private fun app() = application {
    val container = remember { AppContainer().also { it.init() } }
    StartupTrace.mark("container-ready")
    val appIcon = remember { appIconPainter() }
    val engineVersion = remember { container.engine.bundledVersion() }
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
    var windowVisible by remember { mutableStateOf(System.getProperty("frpc.silent") != "true") }

    LaunchedEffect(Unit) {
        StartupTrace.mark("first-composition")
        AppActivation.requests.collect {
            windowVisible = true
        }
    }

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
                val proxyTotal = activeCounts.proxies
                if (proxyTotal > 0) {
                    val online = proxyStatus?.all?.count { it.status == "running" } ?: 0
                    append(" · 代理 ").append(online).append('/').append(proxyTotal)
                }
                val visitorTotal = activeCounts.visitors
                if (visitorTotal > 0) {
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            AppTray.install(
                onShow = { windowVisible = true },
                onStop = { container.supervisor.stop() },
                onQuit = {
                    container.shutdown()
                    AppTray.remove()
                    SingleInstance.release()
                    exitApplication()
                },
            )
        }
        StartupTrace.mark("tray-ready")
    }
    LaunchedEffect(trayTooltip, runState.phase) {
        AppTray.updateTooltip(trayTooltip)
        AppTray.updateRunning(runState.phase == FrpcSupervisor.Phase.RUNNING)
    }
    DisposableEffect(Unit) { onDispose { AppTray.remove() } }

    if (windowVisible) {
        Window(
            icon = appIcon,
            onCloseRequest = {
                if (settings.closeToTray) {
                    windowVisible = false
                } else {
                    container.shutdown()
                    SingleInstance.release()
                    exitApplication()
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
            }
            LaunchedEffect(Unit) {
                while (!window.isShowing) delay(5)
                StartupTrace.mark("window-showing")
            }
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
                        version = engineVersion,
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
