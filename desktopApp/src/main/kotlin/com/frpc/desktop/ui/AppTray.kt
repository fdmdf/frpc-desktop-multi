package com.frpc.desktop.ui

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import java.awt.Window
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 系统托盘。
 *
 * ## 菜单结构：只留常用入口
 * ```
 * 显示主界面
 * 设置
 * 关于
 * ────────
 * 退出
 * ```
 * 没有图标列、没有子菜单、只有一条分隔线。一条菜单项 = 一个 [TrayEvent]，
 * 点击后**先关菜单再派发事件**（否则「设置 / 关于」要弹的窗口会被置顶的菜单压住）。
 *
 * ## 菜单为什么自己画（JPopupMenu 里塞一个自绘 JPanel）
 * - **DPI**：`JMenuItem` 的行高、图标间隙由 L&F 决定，高分屏上经常出现半像素行高、
 *   1px 分隔线被抹成 2px 灰边、文字截断。自己画可以把每个尺寸**对齐到整设备像素**。
 * - **主题**：L&F 的暗色适配不可靠（Windows L&F 在暗色模式下经常画成黑底黑字），
 *   配色全部由 [Palette] 显式指定。
 * - **中文**：AWT 的 `PopupMenu` 由系统按「菜单字体」绘制，`MenuItem.setFont` 不生效，
 *   系统菜单字体没有 CJK 回退，中文会渲染成方框（□□）；自己画走 Java 的字体回退。
 * - **外壳仍用 `JPopupMenu`**：它自带「点击任意位置关闭」的**原生鼠标 grab**
 *   （`BasicPopupMenuUI.MouseGrabber` → `SunToolkit.grab()`，屏幕任意处按下都收得到事件）、
 *   失焦 / UngrabEvent 关闭、弹层窗口管理。这部分自己实现不了（`sun.awt` 未导出）。
 *
 * ## DPI / 缩放：逻辑像素 + 设备像素对齐
 * JDK 9+（JEP 263）下：窗口坐标与 `GraphicsConfiguration.getBounds()` 都是**逻辑像素**，
 * `GraphicsConfiguration.getDefaultTransform()` 给出「逻辑 → 设备」的倍率（150% 屏 = 1.5）。
 * 于是：
 * - **字号固定 13pt 不乘倍率**。字体按 pt 走，JDK 已经把 DPI 算进去了；再乘一次会变成
 *   「放大两次」（150% 屏上 13pt 会被画成 18pt 的物理大小）。
 * - **行高 / 内边距 / 宽度**由 `FontMetrics` 实测得到（逻辑像素），
 *   再用 `snap(v) = round(v * scale) / scale` 对齐到整设备像素，
 *   避免 125% / 150% 这类非整数倍率下出现半像素的行边界。
 * - **1px 边框与分隔线用 `1 / scale` 逻辑像素的线宽** —— 正好等于 1 个设备像素，
 *   任何倍率下都是清晰的发丝线，不会被拉成 1.5px 的灰边。
 * - **每次弹出都重新读一次倍率**：多屏混插（100% + 200%）时跟着托盘所在的那个屏走。
 *
 * ## 定位：贴着托盘所在的那条边，放不下就翻转 / 内收
 * 托盘图标的坐标 AWT 不给，用鼠标当前位置当锚点（点托盘时鼠标就在图标上），
 * 再用 `Toolkit.getScreenInsets()` 判断任务栏在哪条边：
 * - 任务栏在下 → 菜单底边贴任务栏上沿；在上 → 顶边贴任务栏下沿；
 *   在左 / 右 → 纵向以图标为中心，横向放到图标旁边。
 * - 最后统一按「可用区」（屏幕边界扣掉任务栏）做一次 clamp：
 *   右边放不下就往左收，下方放不下就往上收。
 *
 * ## 关闭：外部点击 / 失焦 / Esc / 再点托盘
 * - **外部点击**：`JPopupMenu` 的原生 grab，屏幕任意位置按下都会把菜单收掉。
 * - **失焦**：`SunToolkit.dismissPopupOnFocusLostIfNeeded` + 弹层窗口的
 *   `WindowFocusListener`（Alt+Tab 走这一条）。
 * - **Esc**：在弹层上注册 `WHEN_IN_FOCUSED_WINDOW` 的 Escape 绑定
 *   （Windows LAF 没有给 `PopupMenu.selectedWindowInputMapBindings` 配 Esc，得自己挂）。
 * - **再点托盘图标**：grab 会先把菜单收掉；[AUTO_REOPEN_GUARD_MS] 内不允许自动重开，
 *   避免同一次点击把它「关掉又打开」。
 * - **释放资源**：收起时 `popup.isVisible = false`（弹层窗口回 PopupFactory），
 *   再把 1x1 的锚点窗口 `dispose()`。
 *
 * ## 平台差异
 * - **Linux**：`SystemTray.isSupported()` 为 false 时 [install] 直接返回 false，应用照常跑。
 * - **圆角**：弹层窗口不支持每像素透明，四角保持直角（与 Windows 原生菜单一致），
 *   也避免在不支持合成的 Linux 上出现四个黑角。
 * - **macOS**：`isPopupTrigger` 在 `mousePressed` 触发，Windows / Linux 在 `mouseReleased`；
 *   两个回调都挂了，各平台只会命中其中一个。
 * - **托盘图标**：macOS 走模板图由系统反色，Windows / Linux 用按 DPI 选尺寸的彩色图标。
 */
object AppTray {
    /** 与 scripts/gen-tray-icons/GenTrayIcons.java 的 COLOR_SIZES 保持一致 */
    private val TRAY_SIZES = intArrayOf(16, 20, 22, 24, 32, 40, 48, 64, 128, 256)

    /** 同一次物理点击可能被 pressed / released 各投递一次 */
    private const val TOGGLE_DEDUPE_MS = 50L
    /** 自动关闭（外部点击 / 失焦）后多久内不允许由托盘点击重新打开 */
    private const val AUTO_REOPEN_GUARD_MS = 250L

    private var icon: TrayIcon? = null
    private var handler: ((TrayEvent) -> Unit)? = null
    private var menu: TrayMenu? = null
    private var state: TrayState = TrayState()
    private var lastToggleMs = 0L
    private var lastAutoCloseMs = 0L

    fun install(onEvent: (TrayEvent) -> Unit): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val image = loadIcon() ?: return false
        handler = onEvent
        val trayIcon = TrayIcon(image, "Frpc-Android-Multi").apply { isImageAutoSize = true }
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) toggleMenu()
            }

            override fun mouseReleased(e: MouseEvent) {
                // Windows / Linux 的右键在 released 才是 popupTrigger；左键统一在这里收
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON1) toggleMenu()
            }
        })
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
        true
    }.getOrDefault(false)

    fun updateTooltip(text: String) {
        runCatching { icon?.toolTip = text }
    }

    /** 状态变化：菜单开着就按新状态重绘（目前只有深浅色），关着就下次打开时生效 */
    fun update(state: TrayState) {
        this.state = state
        onEdt { menu?.applyState(state) }
    }

    /** 关掉菜单并摘掉托盘图标 */
    fun remove() {
        onEdt {
            menu?.close(auto = false)
            menu = null
        }
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        icon = null
        handler = null
    }

    // ---------- 弹出 / 关闭 ----------

    private fun toggleMenu() {
        val now = System.currentTimeMillis()
        if (now - lastToggleMs < TOGGLE_DEDUPE_MS) return
        lastToggleMs = now
        onEdt {
            val m = menu
            if (m != null && !m.isClosed) {
                m.close(auto = false)
            } else if (now - lastAutoCloseMs > AUTO_REOPEN_GUARD_MS) {
                // 刚刚是因为「点托盘 → 菜单自动收起」而关掉的：这次点击就是关菜单，不再打开
                showMenu()
            }
        }
    }

    private fun showMenu() {
        val p = resolvePlacement()
        val m = TrayMenu(p.config, state, ::dispatch, ::onMenuClosed)
        m.place(p)
        m.open()
        menu = m
    }

    private fun dispatch(event: TrayEvent) {
        handler?.invoke(event)
    }

    private fun onMenuClosed(auto: Boolean) {
        menu = null
        if (auto) lastAutoCloseMs = System.currentTimeMillis()
    }

    // ---------- 图标 ----------

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
        val scale = screenScale(null)
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

    // ---------- 自检 ----------

    /**
     * 离屏渲染菜单，用来核对中文有没有渲染成方框、配色 / 行高 / 分隔线 / 禁用态 / 悬停态对不对。
     * 用 `printAll()` 而不是截屏：菜单是独立弹层窗口，无交互桌面上只能拍到桌面底色。
     */
    internal fun renderPreview(dark: Boolean, disabled: Boolean = false, hover: Int = -1): BufferedImage {
        val palette = Palette(dark)
        val entries = menuEntries().mapIndexed { i, e ->
            // 禁用态没有默认入口会用到，自检里把「关于」置灰来验证样式
            if (disabled && i == 2) e.copy(enabled = false) else e
        }
        val scale = screenScale(null)
        val panel = MenuPanel(palette, scale) {}
        panel.entries = entries
        panel.hoverForTest = hover
        val size = panel.measureSize()
        panel.setSize(size)
        // 按设备像素出图：边框 / 分隔线的发丝线是 1 个设备像素，
        // 只画逻辑像素的话会被 AA 抹淡，看不出真实效果
        val img = BufferedImage(
            (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val g = img.createGraphics()
        g.scale(scale, scale)
        g.color = palette.bg
        g.fillRect(0, 0, size.width, size.height)
        panel.printAll(g)
        g.dispose()
        return img
    }
}

// ---------- 菜单弹层 ----------

/** 任务栏所在的那条边：决定菜单往哪个方向弹 */
private enum class TaskbarEdge { TOP, BOTTOM, LEFT, RIGHT }

/** 一次弹出所需的屏幕信息；全部是**逻辑像素** */
private class Placement(
    val config: GraphicsConfiguration,
    val anchor: Point,
    /** 屏幕边界扣掉任务栏后的可用区 */
    val usable: Rectangle,
    val edge: TaskbarEdge,
    val iconSize: Dimension,
)

/**
 * 弹出位置：托盘图标的坐标 AWT 拿不到，用鼠标位置当锚点（点托盘时鼠标就在图标上）。
 */
private fun resolvePlacement(): Placement {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val info = runCatching { MouseInfo.getPointerInfo() }.getOrNull()
    val device = info?.device ?: env.defaultScreenDevice
    val gc = device.defaultConfiguration
    val bounds = gc.bounds
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }
        .getOrDefault(Insets(0, 0, 0, 0))
    val usable = Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        (bounds.width - insets.left - insets.right).coerceAtLeast(1),
        (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
    val scale = screenScale(gc)
    var anchor = info?.location ?: Point(usable.x + usable.width - 40, usable.y + usable.height - 20)
    // 少数 JDK 组合下 MouseInfo 返回设备像素：换算回逻辑像素（窗口坐标、屏幕边界都是逻辑的）
    if (!bounds.contains(anchor)) {
        val logical = Point((anchor.x / scale).roundToInt(), (anchor.y / scale).roundToInt())
        if (bounds.contains(logical)) anchor = logical
    }
    val edge = when {
        insets.bottom > 0 -> TaskbarEdge.BOTTOM
        insets.top > 0 -> TaskbarEdge.TOP
        insets.left > 0 -> TaskbarEdge.LEFT
        insets.right > 0 -> TaskbarEdge.RIGHT
        else -> TaskbarEdge.BOTTOM
    }
    val iconSize = runCatching { SystemTray.getSystemTray().trayIconSize }
        .getOrDefault(Dimension(16, 16))
    return Placement(gc, anchor, usable, edge, iconSize)
}

/**
 * 托盘菜单：`JPopupMenu` 当外壳（自带 grab / 失焦关闭 / 弹层窗口），
 * 内容是一个全自绘的 [MenuPanel]。每次弹出新建，收起即释放。
 */
private class TrayMenu(
    config: GraphicsConfiguration,
    state: TrayState,
    private val onEvent: (TrayEvent) -> Unit,
    private val onClosed: (auto: Boolean) -> Unit,
) {
    private val palette = Palette(state.dark)
    private val scale = screenScale(config)
    private val panel = MenuPanel(palette, scale, ::pick)
    /**
     * 弹层需要一个「invoker」：1x1 全透明窗口，放在菜单要出现的位置上。
     * 原生 grab 也是挂在这个窗口上的（见 `MouseGrabber.grabWindow`）。
     */
    private val anchor = JWindow(config).apply {
        setSize(1, 1)
        background = Color(0, 0, 0, 0)
        // POPUP：不进任务栏、不抢焦点（JWindow 本来就是无边框窗口）
        type = Window.Type.POPUP
        focusableWindowState = false
    }
    private val popup = JPopupMenu()
    private var closed = false
    private var nextCloseAuto = true
    private var anchorLocation = Point(0, 0)

    val isClosed: Boolean get() = closed

    init {
        panel.entries = menuEntries()
        val size = panel.measureSize()
        panel.preferredSize = size
        popup.apply {
            layout = BorderLayout()
            // 必须是重量级弹层：轻量弹层会把菜单画进 1x1 的锚点窗口里，屏幕上什么都看不到
            isLightWeightPopupEnabled = false
            border = BorderFactory.createEmptyBorder()
            background = palette.bg
            isOpaque = true
            add(panel, BorderLayout.CENTER)
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = Unit
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = fireClosed()
                override fun popupMenuCanceled(e: PopupMenuEvent?) {
                    nextCloseAuto = true
                    fireClosed()
                }
            })
            // Windows LAF 没给 PopupMenu 配 Esc 绑定，自己挂一条（弹层窗口有焦点时生效）
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "tray.close")
            actionMap.put("tray.close", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = close(auto = true)
            })
        }
    }

    /** 定位：先按任务栏那条边定向，再统一按可用区 clamp（放不下就翻转 / 内收） */
    fun place(p: Placement) {
        val w = panel.preferredSize.width
        val h = panel.preferredSize.height
        val gap = (4 * scale).roundToInt()
        val x = when (p.edge) {
            TaskbarEdge.LEFT -> p.anchor.x + p.iconSize.width + gap
            TaskbarEdge.RIGHT -> p.anchor.x - w - gap
            else -> p.anchor.x - w / 2
        }
        val y = when (p.edge) {
            TaskbarEdge.BOTTOM -> p.usable.y + p.usable.height - h
            TaskbarEdge.TOP -> p.usable.y
            else -> p.anchor.y - h / 2
        }
        anchorLocation = Point(
            x.coerceIn(p.usable.x, (p.usable.x + p.usable.width - w).coerceAtLeast(p.usable.x)),
            y.coerceIn(p.usable.y, (p.usable.y + p.usable.height - h).coerceAtLeast(p.usable.y)),
        )
    }

    fun open() {
        anchor.location = anchorLocation
        anchor.isVisible = true
        popup.show(anchor, 0, 0) // 弹层左上角 = 锚点窗口位置
        // 弹层窗口是 focusable 的（PopupFactory 见到「非 MenuElement 且可聚焦」的内容就设为可聚焦），
        // 这里补一次焦点请求，并挂上失焦关闭
        SwingUtilities.getWindowAncestor(panel)?.addWindowFocusListener(
            object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent?) = close(auto = true)
            } as WindowFocusListener,
        )
        panel.requestFocusInWindow()
    }

    /** 主题变了就关掉：配色是建菜单时定好的，半套深浅色比重新弹一次更糟 */
    fun applyState(state: TrayState) {
        if (state.dark != palette.dark) close(auto = false)
    }

    fun close(auto: Boolean) {
        if (closed) return
        nextCloseAuto = auto
        popup.isVisible = false // → popupMenuWillBecomeInvisible → fireClosed()
        fireClosed() // 监听没触发时兜底
    }

    private fun fireClosed() {
        if (closed) return
        closed = true
        // 释放资源：弹层由 PopupFactory 回收，锚点窗口是我们自己的，必须显式 dispose
        runCatching { anchor.isVisible = false }
        runCatching { anchor.dispose() }
        onClosed(nextCloseAuto)
    }

    private fun pick(entry: MenuEntry) {
        if (!entry.enabled) return
        close(auto = false)
        entry.event?.let { onEvent(it) }
    }
}

// ---------- 菜单面板（全自绘） ----------

/** 一条菜单项；`event == null` 表示分隔线 */
private data class MenuEntry(
    val text: String,
    val event: TrayEvent?,
    val enabled: Boolean = true,
) {
    val isSeparator: Boolean get() = event == null
}

private fun menuEntries(): List<MenuEntry> = listOf(
    MenuEntry("显示主界面", TrayEvent.ShowWindow),
    MenuEntry("设置", TrayEvent.OpenSettings),
    MenuEntry("关于", TrayEvent.About),
    MenuEntry("", null),
    MenuEntry("退出", TrayEvent.Quit),
)

/** 菜单字号：**固定值**，DPI 由 JDK 处理（见 AppTray 的 DPI 说明） */
private const val MENU_FONT_SIZE = 13

private val MENU_FONT: Font by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val candidates = listOf(
        "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun",
        "Noto Sans CJK SC", "PingFang SC",
    )
    val installed = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    }.getOrDefault(emptySet())
    val hit = candidates.firstOrNull { it in installed }
    Font(hit ?: Font.SANS_SERIF, Font.PLAIN, MENU_FONT_SIZE)
}

/** 量出来的行位置与尺寸，单位都是逻辑像素，且已对齐到整设备像素 */
private class Metrics(
    val fm: FontMetrics,
    val padX: Float,
    val padV: Float,
    val rowH: Float,
    val sep: Float,
    val width: Int,
    val height: Int,
    /** 每一行的上边（含上下留白） */
    val tops: FloatArray,
)

private class MenuPanel(
    private val palette: Palette,
    private val scale: Double,
    private val onPick: (MenuEntry) -> Unit,
) : JPanel() {
    var entries: List<MenuEntry> = emptyList()
        set(value) {
            field = value
            metrics = null
        }

    private var metrics: Metrics? = null
    /** 仅供自检：直接指定悬停在哪一行（`hover` 平时由鼠标事件驱动） */
    var hoverForTest = -1
        set(value) {
            field = value
            hover = value
        }
    private var hover = -1
    private var pressed = -1

    init {
        isOpaque = true
        isFocusable = true
        font = MENU_FONT
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                pressed = rowAt(e.y)
            }

            override fun mouseReleased(e: MouseEvent) {
                val i = rowAt(e.y)
                if (i >= 0 && i == pressed) pick(i)
                pressed = -1
            }

            override fun mouseExited(e: MouseEvent) = setHover(-1)
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = setHover(rowAt(e.y))
            override fun mouseDragged(e: MouseEvent) = setHover(rowAt(e.y))
        })
    }

    /** 先量一次，弹层才能按内容定尺寸（行高 / 宽度都由真实字体量出来，不会截断） */
    fun measureSize(): Dimension {
        val m = metrics ?: measure()
        return Dimension(m.width, m.height)
    }

    private fun measure(): Metrics {
        val fm = getFontMetrics(MENU_FONT)
        /** 对齐到整设备像素：125% / 150% 这类倍率下不会出现半像素的行边界 */
        fun snap(v: Double): Float = ((v * scale).roundToInt().toDouble() / scale).toFloat()
        val padX = snap(14.0)
        val padV = snap(6.0)
        val rowH = snap(max(26.0, fm.height + 10.0))
        val sep = snap(9.0)
        val tops = FloatArray(entries.size)
        var y = padV
        for (i in entries.indices) {
            tops[i] = y
            y += if (entries[i].isSeparator) sep else rowH
        }
        var textW = 0
        entries.forEach { if (!it.isSeparator) textW = max(textW, fm.stringWidth(it.text)) }
        val width = (textW + 2 * padX).roundToInt().coerceIn(120, 280)
        val height = ceil(y.toDouble() + padV).toInt()
        return Metrics(fm, padX, padV, rowH, sep, width, height, tops).also { metrics = it }
    }

    override fun paintComponent(g: Graphics) {
        val m = metrics ?: measure()
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val w = width.toFloat()
        val h = height.toFloat()
        val hairline = (1f / scale).toFloat()

        g2.color = palette.bg
        g2.fillRect(0, 0, width, height)

        // 悬停块左右各内缩一点；两条竖边也对齐到设备像素，免得出现半像素的糊边
        val insetX = snapDown(m.padX * 0.45f, scale)
        for (i in entries.indices) {
            val e = entries[i]
            if (e.isSeparator) {
                g2.color = palette.separator
                g2.stroke = BasicStroke(hairline)
                // 横线要落在「半个设备像素」上，1 设备像素的线宽才刚好铺满一整行
                val ly = ((floor((m.tops[i] + m.sep / 2f) * scale) + 0.5) / scale).toFloat()
                g2.draw(Line2D.Float(insetX, ly, w - insetX, ly))
                continue
            }
            if (i == hover && e.enabled) {
                g2.color = palette.selBg
                val right = snapDown(w - m.padX * 0.45f, scale)
                g2.fill(Rectangle2D.Float(insetX, m.tops[i], right - insetX, m.rowH))
            }
            g2.font = MENU_FONT
            g2.color = when {
                !e.enabled -> palette.disabled
                i == hover -> palette.selFg
                else -> palette.fg
            }
            g2.drawString(e.text, m.padX, m.tops[i] + (m.rowH + m.fm.ascent - m.fm.descent) / 2f)
        }

        // 边框：线宽 = 1 个设备像素，路径偏移半个设备像素，任何倍率下都是清晰的细线
        g2.color = palette.border
        g2.stroke = BasicStroke(hairline)
        val half = hairline / 2f
        g2.draw(Rectangle2D.Float(half, half, w - 2 * half, h - 2 * half))
    }

    private fun rowAt(y: Int): Int {
        val m = metrics ?: return -1
        for (i in entries.indices) {
            val top = m.tops[i]
            val bottom = top + if (entries[i].isSeparator) m.sep else m.rowH
            if (y >= top && y < bottom) return if (entries[i].isSeparator) -1 else i
        }
        return -1
    }

    private fun setHover(index: Int) {
        if (hover == index) return
        hover = index
        repaint()
    }

    private fun pick(index: Int) {
        entries.getOrNull(index)?.let { onPick(it) }
    }
}

// ---------- 公共小工具 ----------

/** 把逻辑像素向下对齐到整设备像素（画矩形边界用，避免半像素糊边） */
private fun snapDown(v: Float, scale: Double): Float = (floor(v * scale) / scale).toFloat()

/** 逻辑像素 → 设备像素的倍率（100% = 1.0，150% = 1.5）；每次弹出都重读，支持多屏混插 */
internal fun screenScale(gc: GraphicsConfiguration?): Double {
    val config = gc ?: runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    }.getOrNull()
    val s = runCatching { config?.defaultTransform?.scaleX }.getOrNull() ?: 1.0
    return if (s.isFinite() && s >= 0.25) s.coerceIn(0.5, 4.0) else 1.0
}

/** 在 EDT 上执行；install 常从 Compose 主线程调用，直接 invokeAndWait 会死锁 */
private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        runCatching { SwingUtilities.invokeAndWait(block) }
    }
}

/** 菜单配色：不依赖 L&F 的暗色适配（Windows LAF 在暗色模式下经常画成黑底黑字） */
private class Palette(val dark: Boolean) {
    val bg = if (dark) Color(0x2B, 0x2B, 0x2B) else Color(0xFF, 0xFF, 0xFF)
    val fg = if (dark) Color(0xEE, 0xEE, 0xEE) else Color(0x1F, 0x1F, 0x1F)
    val disabled = if (dark) Color(0x7A, 0x7A, 0x7A) else Color(0xA6, 0xA6, 0xA6)
    val selBg = if (dark) Color(0x2F, 0x5A, 0x8C) else Color(0xE8, 0xF0, 0xFE)
    val selFg = if (dark) Color(0xFF, 0xFF, 0xFF) else Color(0x1F, 0x1F, 0x1F)
    val border = if (dark) Color(0x45, 0x45, 0x45) else Color(0xE0, 0xE0, 0xE0)
    val separator = if (dark) Color(0x4A, 0x4A, 0x4A) else Color(0xE8, 0xE8, 0xE8)
}

// ---------- 事件 / 状态 ----------

/**
 * 托盘菜单事件。菜单项与「左键单击托盘图标」都汇到调用方的那一个 `when` 里。
 */
sealed interface TrayEvent {
    /** 显示主界面（已被隐藏时唤到前台） */
    data object ShowWindow : TrayEvent

    data object OpenSettings : TrayEvent

    data object About : TrayEvent

    data object Quit : TrayEvent
}

/** 托盘菜单的实时状态 */
data class TrayState(
    val dark: Boolean = false,
)
