package com.frpc.desktop.ui

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 系统托盘。
 *
 * ## 为什么菜单用 Swing（JPopupMenu）而不是 AWT（PopupMenu）
 * AWT 的菜单在 Windows 上由系统按「菜单字体」绘制，`MenuItem.setFont` 不生效 ——
 * 系统菜单字体不含中文字形且没有 CJK 回退，中文会渲染成方框（□□）。
 * Swing 的菜单项是 Java 自己绘制的，组件字体一定生效，中文由 Java 的字体回退兜住。
 *
 * ## 图标为什么不是 SVG（各平台的真实限制）
 * - **托盘图标**：必须是 AWT 的 `TrayIcon`，只接受 `java.awt.Image`（位图）。
 *   macOS 还要求它是一张 alpha 蒙版（模板图），颜色被系统忽略。SVG 在这里无路可走。
 * - **菜单项图标**：`JMenuItem.setIcon` 接受任意 `javax.swing.Icon`，理论上可以先
 *   把 SVG 渲染成位图再交给它；但 **Java 标准库没有 SVG 渲染器**，`ImageIO` 也不认 `.svg`
 *   （`ImageIO.getReaderFileSuffixes()` 里没有 svg）。引入 Batik 等于为一个托盘菜单
 *   拖进 ~2.5 MB 依赖，不划算。
 *
 *   **降级方案**：由 `scripts/gen-tray-icons/GenTrayIcons.java` 在构建期把矢量路径
 *   预渲染成 **16px(@1x) / 32px(@2x)** 两套 PNG 蒙版（纯黑 + alpha），运行时按 DPI 选一张、
 *   再按菜单前景色重新着色。效果是等价的，且深浅色主题共用同一份资源。
 *
 * ## 深浅色
 * 托盘图标：macOS 走模板图由系统反色；Windows/Linux 用彩色图标（自带底板，深浅底都看得清）。
 * 菜单：由调用方把当前主题（跟随系统时用 `OsTheme.prefersDark()` 探测）通过 `TrayState.dark`
 * 传进来，这里显式设置 UIManager 与组件的前景色/背景色/选中色。
 * 主题切换会重建菜单 —— Swing 的 UI delegate 在建组件时就把 UIManager 的值读走了，
 * 事后改 UIManager 对已存在的组件无效。
 */
object AppTray {
    /** 与 scripts/gen-tray-icons/GenTrayIcons.java 的 COLOR_SIZES 保持一致 */
    private val TRAY_SIZES = intArrayOf(16, 20, 22, 24, 32, 40, 48, 64, 128, 256)

    private var icon: TrayIcon? = null
    private var menu: JPopupMenu? = null
    /** 菜单的锚点窗口：Swing 弹出菜单需要一个可见的 invoker，用 1x1 全透明窗口充当 */
    private var anchor: JWindow? = null
    private var actions: TrayActions? = null
    private var current: TrayState = TrayState()
    private var builtDark: Boolean? = null
    /** 菜单项引用：`update()` 时按状态改文案 / 图标 / 可用态，不重建菜单 */
    private var itemWindow: JMenuItem? = null
    private var itemRun: JMenuItem? = null
    private var itemReload: JMenuItem? = null
    private var itemStatus: JMenuItem? = null
    private var itemAutoStart: JMenuItem? = null
    private var itemCloseToTray: JMenuItem? = null
    private var itemUpdate: JMenuItem? = null

    fun install(actions: TrayActions): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val image = loadIcon() ?: return false
        this.actions = actions

        onEdt {
            anchor = JWindow().apply {
                setSize(1, 1)
                background = Color(0, 0, 0, 0) // 全透明，屏幕上只看得见菜单
                type = Window.Type.POPUP // 不进任务栏 / 不抢焦点
                isVisible = true
            }
        }

        val trayIcon = TrayIcon(image, "Frpc-Android-Multi").apply { isImageAutoSize = true }
        // 左键单击 = 显示主窗口（不做切换：托盘图标点一下把窗口藏起来不符合预期）
        trayIcon.addActionListener { actions.onShowWindow() }
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showAtMouse()
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showAtMouse()
            }
        })
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
        true
    }.getOrDefault(false)

    fun updateTooltip(text: String) {
        runCatching { icon?.toolTip = text }
    }

    /** 按最新状态刷新菜单项的文案 / 图标 / 可用态 / 勾选态 */
    fun update(state: TrayState) {
        current = state
        onEdt {
            // 主题变了必须重建：UI delegate 在 installDefaults 时就把配色快照走了
            if (menu == null || builtDark != state.dark) {
                buildMenu(state)
            } else {
                applyState(state)
            }
        }
    }

    fun remove() {
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        onEdt {
            menu?.isVisible = false
            anchor?.dispose()
        }
        icon = null
        menu = null
        anchor = null
        actions = null
        builtDark = null
        itemWindow = null
        itemRun = null
        itemReload = null
        itemStatus = null
        itemAutoStart = null
        itemCloseToTray = null
        itemUpdate = null
    }

    /** 在鼠标当前位置弹出菜单（右键点击托盘图标 / --tray-test 自检） */
    fun showAtMouse() {
        val p = runCatching { MouseInfo.getPointerInfo().location }.getOrNull() ?: return
        showMenuAt(p.x, p.y)
    }

    /** 在屏幕坐标 (x, y) 弹出菜单；贴边时 JPopupMenu 会自动向上/向左翻转 */
    fun showMenuAt(x: Int, y: Int) {
        onEdt {
            val m = menu ?: return@onEdt
            val a = anchor ?: return@onEdt
            a.setLocation(x, y)
            m.show(a, 0, 0)
        }
    }

    /** 仅供 --tray-test 自检：拿到菜单本体核对中文有没有渲染成方框 */
    internal fun popupForTest(): JPopupMenu? = menu

    // ---------- 菜单构建 ----------

    private fun buildMenu(state: TrayState) {
        val act = actions ?: return
        val p = Palette(state.dark)
        val font = menuFont(fontSize())
        val s = screenScale()
        val padV = (5 * s).roundToInt().coerceIn(5, 9)
        val padL = (10 * s).roundToInt().coerceIn(10, 16)
        val padR = (14 * s).roundToInt().coerceIn(14, 22)
        val gap = (8 * s).roundToInt().coerceIn(8, 14)

        // 必须在建组件之前先改 UIManager（见文件头注释）
        applyUiDefaults(p, font)

        val popup = JPopupMenu().apply {
            this.font = font
            background = p.bg
            foreground = p.fg
            isOpaque = true
            // 必须是重量级弹层：默认（轻量级）弹层会把菜单画进「锚点窗口」内部，
            // 而锚点是一个 1x1 的透明窗口 —— 菜单会被裁成 1x1，屏幕上什么都看不到。
            // 重量级弹层由 Swing 自己开一个真实窗口承载，不受锚点尺寸限制。
            isLightWeightPopupEnabled = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(p.border),
                BorderFactory.createEmptyBorder(padV, 0, padV, 0),
            )
        }

        fun addItem(text: String, glyph: Glyph, action: () -> Unit): JMenuItem {
            val m = JMenuItem(text)
            m.font = font
            m.background = p.bg
            m.foreground = p.fg
            m.isOpaque = true
            m.margin = Insets(padV, padL, padV, padR)
            m.iconTextGap = gap
            m.icon = glyphIcon(glyph, p.fg)
            m.disabledIcon = glyphIcon(glyph, p.disabled)
            m.addActionListener { action() }
            popup.add(m)
            return m
        }

        fun addSeparator() {
            popup.add(JPopupMenu.Separator().apply {
                background = p.bg
                foreground = p.separator
                // 分隔线上下留白，避免和菜单项挤在一起
                border = BorderFactory.createEmptyBorder(padV - 1, 0, padV - 1, 0)
            })
        }

        itemWindow = addItem("显示主窗口", Glyph.WINDOW) {
            if (current.windowVisible) act.onHideWindow() else act.onShowWindow()
        }
        addSeparator()
        itemRun = addItem("启动 frpc", Glyph.PLAY) { act.onToggleRun() }
        itemReload = addItem("重载配置", Glyph.RELOAD) { act.onReload() }
        itemStatus = addItem("打开状态页", Glyph.WEB) { act.onOpenStatusPage() }
        addSeparator()
        itemAutoStart = addItem("应用启动时自动运行", Glyph.BOX) {
            act.onAutoStartChange(current.autoStart != true)
        }
        itemCloseToTray = addItem("关闭窗口时最小化到托盘", Glyph.BOX) {
            act.onCloseToTrayChange(current.closeToTray != true)
        }
        addSeparator()
        addItem("设置", Glyph.GEAR) { act.onOpenSettings() }
        itemUpdate = addItem("检查更新", Glyph.UPDATE) { act.onCheckUpdate() }
        addItem("关于", Glyph.INFO) { act.onAbout() }
        addSeparator()
        addItem("退出", Glyph.QUIT) { act.onQuit() }

        // 不设 accelerator：托盘菜单无法注册全局热键（要 JNA/JIntellitype），
        // 而只在主窗口聚焦时生效的「半快捷键」反而会误导用户，故一律不标。
        menu = popup
        builtDark = state.dark
        applyState(state)
    }

    private fun applyState(state: TrayState) {
        val p = Palette(state.dark)
        itemWindow?.text = if (state.windowVisible) "隐藏主窗口" else "显示主窗口"

        itemRun?.apply {
            when (state.run) {
                TrayRunState.RUNNING -> {
                    text = "停止 frpc"
                    icon = glyphIcon(Glyph.STOP, p.fg)
                    disabledIcon = glyphIcon(Glyph.STOP, p.disabled)
                    isEnabled = true
                }
                TrayRunState.STARTING -> {
                    text = "启动中…"
                    icon = glyphIcon(Glyph.PLAY, p.disabled)
                    isEnabled = false
                }
                TrayRunState.STOPPING -> {
                    text = "停止中…"
                    icon = glyphIcon(Glyph.STOP, p.disabled)
                    isEnabled = false
                }
                TrayRunState.IDLE -> {
                    text = "启动 frpc"
                    icon = glyphIcon(Glyph.PLAY, p.fg)
                    disabledIcon = glyphIcon(Glyph.PLAY, p.disabled)
                    isEnabled = state.canStart
                }
            }
        }
        val running = state.run == TrayRunState.RUNNING
        itemReload?.isEnabled = running
        itemStatus?.isEnabled = running && state.statusPort != null
        itemAutoStart?.apply {
            icon = glyphIcon(if (state.autoStart) Glyph.BOX_CHECK else Glyph.BOX, p.fg)
            disabledIcon = glyphIcon(if (state.autoStart) Glyph.BOX_CHECK else Glyph.BOX, p.disabled)
        }
        itemCloseToTray?.apply {
            icon = glyphIcon(if (state.closeToTray) Glyph.BOX_CHECK else Glyph.BOX, p.fg)
            disabledIcon = glyphIcon(if (state.closeToTray) Glyph.BOX_CHECK else Glyph.BOX, p.disabled)
        }
        itemUpdate?.apply {
            text = state.updateLabel
            isEnabled = state.updateEnabled
        }
    }

    private fun applyUiDefaults(p: Palette, font: Font) {
        // 尽量贴近原生观感；失败就退回当前 L&F，不影响中文（字体是显式设的）
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        // 三重保险：组件字体 + L&F 默认值 + 复选框项，任一条路径被用到都能拿到中文字体
        UIManager.put("PopupMenu.font", font)
        UIManager.put("MenuItem.font", font)
        UIManager.put("CheckBoxMenuItem.font", font)
        UIManager.put("PopupMenu.background", p.bg)
        UIManager.put("PopupMenu.foreground", p.fg)
        for (prefix in arrayOf("MenuItem", "CheckBoxMenuItem")) {
            UIManager.put("$prefix.background", p.bg)
            UIManager.put("$prefix.foreground", p.fg)
            UIManager.put("$prefix.selectionBackground", p.selBg)
            UIManager.put("$prefix.selectionForeground", p.selFg)
            UIManager.put("$prefix.disabledForeground", p.disabled)
        }
        UIManager.put("Separator.foreground", p.separator)
        UIManager.put("Separator.background", p.bg)
    }

    // ---------- 图标 ----------

    private enum class Glyph(val file: String) {
        WINDOW("window"),
        PLAY("play"),
        STOP("stop"),
        RELOAD("reload"),
        WEB("web"),
        GEAR("gear"),
        UPDATE("update"),
        INFO("info"),
        QUIT("quit"),
        BOX("box"),
        BOX_CHECK("box-check"),
    }

    /** 菜单项字形：按 DPI 选 @1x/@2x，再按前景色着色（资源是纯黑 + alpha 的蒙版） */
    private fun glyphIcon(g: Glyph, color: Color): ImageIcon? {
        val suffix = if (screenScale() >= 1.5) "@2x" else ""
        val img = readPng("/tray/menu/${g.file}$suffix.png") ?: return null
        return ImageIcon(tint(img, color))
    }

    private fun readPng(path: String): BufferedImage? = runCatching {
        AppTray::class.java.getResourceAsStream(path)?.use { ImageIO.read(it) }
    }.getOrNull()

    /** 保留 alpha、替换 RGB —— 与 GenTrayIcons.tint() 同一套算法 */
    private fun tint(src: BufferedImage, color: Color): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val rgb = color.rgb and 0x00FFFFFF
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val a = src.getRGB(x, y) and 0xFF000000.toInt()
                out.setRGB(x, y, a or rgb)
            }
        }
        return out
    }

    /**
     * 按平台 + 缩放挑一张尺寸最接近的托盘图标，避免 AWT 把 512 缩到 16 导致的发虚与边缘杂色。
     * 资源由 scripts/gen-tray-icons/GenTrayIcons.java 生成。
     */
    private fun loadIcon(): Image? = runCatching {
        val stream = AppTray::class.java.getResourceAsStream(trayIconResource())
            ?: AppTray::class.java.getResourceAsStream("/icon.png")
        stream?.use { ImageIO.read(it) }
    }.getOrNull()

    private fun trayIconResource(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val scale = screenScale()
        if (os.contains("mac")) {
            // macOS 菜单栏按模板图处理（AWT 会 setTemplate），只需给出 @1x / @2x 的镂空剪影，
            // 深浅色菜单栏由系统自动反色
            return if (scale >= 1.5) "/tray/tray-mac@2x.png" else "/tray/tray-mac.png"
        }
        // Windows 托盘 16px@100%（120/144/192/240 DPI 对应 20/24/32/40），Linux 常见 22/24
        val base = if (os.contains("win")) 16 else 22
        val target = (base * scale).roundToInt().coerceIn(16, 256)
        val best = TRAY_SIZES.minByOrNull { abs(it - target) } ?: 32
        return "/tray/tray-$best.png"
    }

    private fun screenScale(): Double = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    }.getOrDefault(1.0)

    /** 菜单字号随 DPI 走：96 DPI 用 12（与系统菜单一致），200% 屏放大到 18 */
    private fun fontSize(): Int = (12 * screenScale()).roundToInt().coerceIn(12, 18)

    /** 优先选已安装的中文字族；一个都没有时交给 Java 的字体回退（Swing 自己绘字，能兜住） */
    private fun menuFont(size: Int): Font {
        val candidates = listOf(
            "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun",
            "Noto Sans CJK SC", "PingFang SC",
        )
        val installed = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        val hit = candidates.firstOrNull { it in installed }
        return Font(hit ?: Font.SANS_SERIF, Font.PLAIN, size)
    }

    /** 菜单配色：不依赖 L&F 的暗色适配（Windows L&F 在暗色模式下经常画成黑底黑字） */
    private class Palette(dark: Boolean) {
        val bg = if (dark) Color(0x2B, 0x2B, 0x2B) else Color(0xFA, 0xFA, 0xFA)
        val fg = if (dark) Color(0xEE, 0xEE, 0xEE) else Color(0x1F, 0x1F, 0x1F)
        val fgDim = if (dark) Color(0xA8, 0xA8, 0xA8) else Color(0x6E, 0x6E, 0x6E)
        val disabled = if (dark) Color(0x7A, 0x7A, 0x7A) else Color(0xA0, 0xA0, 0xA0)
        val selBg = if (dark) Color(0x2F, 0x5A, 0x8C) else Color(0xDC, 0xE8, 0xFA)
        val selFg = if (dark) Color(0xFF, 0xFF, 0xFF) else Color(0x0B, 0x3D, 0x91)
        val border = if (dark) Color(0x44, 0x44, 0x44) else Color(0xDA, 0xDA, 0xDA)
        val separator = if (dark) Color(0x48, 0x48, 0x48) else Color(0xE4, 0xE4, 0xE4)
    }

    /** 在 EDT 上执行；install 常从 Compose 主线程调用，直接 invokeAndWait 会死锁 */
    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            runCatching { SwingUtilities.invokeAndWait(block) }
        }
    }
}

/** frpc 在托盘菜单里的粗粒度状态：决定「启动 / 停止」那一项的文案与可用态 */
enum class TrayRunState { IDLE, STARTING, STOPPING, RUNNING }

/** 托盘菜单需要的动作。回调都在 EDT 上触发，涉及 Compose 状态或 IO 的自行切线程 */
data class TrayActions(
    val onShowWindow: () -> Unit,
    val onHideWindow: () -> Unit,
    val onToggleRun: () -> Unit,
    val onReload: () -> Unit,
    val onOpenStatusPage: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCheckUpdate: () -> Unit,
    val onAbout: () -> Unit,
    val onQuit: () -> Unit,
    val onAutoStartChange: (Boolean) -> Unit,
    val onCloseToTrayChange: (Boolean) -> Unit,
)

/** 托盘菜单的实时状态；任何一项变化都通过 AppTray.update() 推一次即可 */
data class TrayState(
    val windowVisible: Boolean = true,
    val run: TrayRunState = TrayRunState.IDLE,
    /** 没有可用配置时「启动 frpc」置灰 */
    val canStart: Boolean = true,
    /** frpc 状态页端口；null = 未启用 webServer，菜单项置灰 */
    val statusPort: Int? = null,
    val autoStart: Boolean = false,
    val closeToTray: Boolean = false,
    val dark: Boolean = false,
    /** 检查结果会就地替换「检查更新」的文案，例如「已是最新 1.3.2」/「有新版本 1.4.0」 */
    val updateLabel: String = "检查更新",
    val updateEnabled: Boolean = true,
)
