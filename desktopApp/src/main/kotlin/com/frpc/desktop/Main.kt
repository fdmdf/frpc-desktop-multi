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
import com.frpc.desktop.ui.AppTheme
import com.frpc.desktop.ui.AppTray
import com.frpc.desktop.ui.HomeScreen
import com.frpc.desktop.ui.LogScreen
import com.frpc.desktop.ui.MonitorScreen
import com.frpc.desktop.ui.ProfilesScreen
import com.frpc.desktop.ui.SettingsScreen
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
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
 * 托盘自检：把托盘菜单弹到屏幕上截图，用来核对中文有没有渲染成方框。
 * 用法：Frpc-Android-Multi.exe --tray-test <输出png>
 */
private fun runTrayTest(args: Array<String>) {
    val target = File(args.getOrNull(args.indexOf("--tray-test") + 1) ?: "tray-test.png")
    if (!AppTray.install(onShow = {}, onStop = {}, onQuit = {})) {
        println("tray not supported")
        exitProcess(4)
    }
    AppTray.updateTooltip("Frpc-Android-Multi · 运行中 · 代理 3/3 · 访问者 1/1")
    AppTray.updateRunning(true)
    val popup = AppTray.popupForTest() ?: run { println("no popup"); exitProcess(5) }
    val frame = Frame().apply {
        isUndecorated = true
        setSize(1, 1)
        setLocation(40, 40)
        isVisible = true
    }
    popup.show(frame, 60, 60)
    Thread.sleep(1_200)
    // PopupMenu 不是 Component，取不到自身坐标：按 show() 的锚点推算（frame 40,40 + 60,60）
    val shot = Robot().createScreenCapture(Rectangle(90, 90, 240, 170))
    ImageIO.write(shot, "png", target)
    println("saved: ${target.absolutePath}")
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

    // 第二个实例启动时会把请求发过来：把窗口显示出来即可
    LaunchedEffect(Unit) {
        AppActivation.requests.collect {
            windowVisible = true
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
    val trayProfiles by container.profiles.profiles.collectAsState()
    val activeProfile = trayProfiles.firstOrNull { it.id == runState.profileId }
    val trayTooltip = buildString {
        append("Frpc-Android-Multi")
        when (runState.phase) {
            FrpcSupervisor.Phase.RUNNING -> {
                append(" · 运行中")
                // 分母只算启用的：禁用的规则 frpc 不注册，算进去就成了永远凑不齐的 0/1
                val proxyTotal = activeProfile?.config?.proxies?.count { it.enabled != false } ?: 0
                if (proxyTotal > 0) {
                    val online = proxyStatus?.all?.count { it.status == "running" } ?: 0
                    append(" · 代理 ").append(online).append('/').append(proxyTotal)
                }
                val visitorTotal = activeProfile?.config?.visitors?.count { it.enabled != false } ?: 0
                if (visitorTotal > 0) {
                    val vOnline = activeProfile!!.config.visitors
                        .count { it.enabled != false && it.name in visitorsOnline }
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
    LaunchedEffect(trayTooltip, runState.phase) {
        AppTray.updateTooltip(trayTooltip)
        AppTray.updateRunning(runState.phase == FrpcSupervisor.Phase.RUNNING)
    }
    DisposableEffect(Unit) { onDispose { AppTray.remove() } }

    if (windowVisible) {
        Window(
            icon = appIconPainter(),
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
