package com.frpc.desktop.ui

import com.frpc.desktop.core.StartupTrace
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Frame
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
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 系统托盘：生命周期只有 install / update / remove 三段，对外的全部符号就是
 * [TrayEvent] + [TrayState] + [AppTray]（其余一律 private）。
 *
 * ## 平台差异
 * | 平台 | 左键单击 | 右键单击 | 托盘图标 |
 * |---|---|---|---|
 * | Windows | 显示主界面 | 弹出菜单 | `tray/tray-<n>.png`，按 DPI 取最接近的一档 |
 * | macOS | 弹出菜单 | 弹出菜单 | `tray/tray-mac.png`（22px@1x）/ `tray-mac@2x.png`（44px@2x） |
 * | Linux | 显示主界面 | 弹出菜单 | 同 Windows，基准尺寸 22 |
 * | 不支持（`SystemTray.isSupported() == false`） | — | — | install 返回 false，应用照常运行 |
 *
 * macOS 单击即开菜单是状态栏的规范，右键也一样；Windows / Linux 才是左键显示主界面。
 * `isPopupTrigger` 的触发时机按平台分：macOS 落在 pressed（普通点击与 Ctrl+点击都算），
 * Windows / Linux 落在 released。两个回调都注册且只注册一次，按平台各命中一个，
 * 另外留了 50ms 的去重窗口兜底（个别 JDK 组合会在 pressed 与 released 各投递一次）。
 * macOS 的图标是纯黑 + alpha 的模板图，深浅色菜单栏由系统反色，所以只有 @1x / @2x 两张。
 *
 * ## 生命周期
 * - **install**：TrayIcon / `JPopupMenu` / `MenuPanel` / 1x1 锚点窗口在 session 里建一次，
 *   所有监听也只在那一刻注册。`session != null` 时直接返回 true —— 幂等，不会重复注册监听、
 *   不会挂第二个 TrayIcon；`SystemTray.isSupported()` 为 false 或图标没读出来就返回 false，
 *   应用照常跑（Linux 桌面环境不提供托盘时走这条路）。
 * - **update**：唯一的推状态入口（深浅色 + 悬停提示都在 [TrayState] 里，不再有第二个入口）。
 *   菜单开着就立刻换配色并 repaint，关着就下次打开时生效 —— 主题切换不必重开菜单。
 * - **remove**：关菜单 → 弹层 `isVisible = false` → dispose 1x1 锚点窗口 → 从系统托盘摘图标 →
 *   清空 session。幂等，退出路径上被调两次也安全。
 *
 * ## 为什么继续自绘（不用 AWT 的 `PopupMenu` / `MenuItem`）
 * AWT 原生菜单由系统按「菜单字体」绘制，`MenuItem.setFont` 不生效，系统菜单字体没有 CJK 回退，
 * 中文会渲染成方框（□□）。所以：外壳继续用 `JPopupMenu`（白拿它自带的弹层窗口管理与
 * `MouseGrabber`），内容是一个全自绘的 `MenuPanel`。职责分工：`MenuPanel` 管鼠标行命中与绘制、
 * `Metrics` 管量算、`Palette` 管配色（不依赖 Windows L&F 的暗色适配，它经常画成黑底黑字）。
 *
 * ## 「点击菜单外不关闭」的根因与本轮修复（三道保险）
 *
 * 根因（本机 JDK 17 `src.zip` 逐条核对 + 实测验证）：弹层锚点原来是一个
 * `focusableWindowState = false` 的 `JWindow`。而 `java/awt/Window.java:2523` 的
 * `isFocusableWindow()` 判定是：
 *   ① `!getFocusableWindowState()` → false；
 *   ② `this is Frame || this is Dialog` → true；
 *   ③ 其余要看 owner 链里有没有正在显示的 Frame / Dialog。
 * 旧实现踩中 ① 和 ③，**锚点永远进不了激活态**。而 JDK 里「点外部关弹层」的唯一实现
 * `BasicPopupMenuUI.MouseGrabber` 会对 invoker 所在的窗口调 `SunToolkit.grab()`
 * （Windows 上是绑定 HWND 的鼠标捕获）—— 窗口进不了激活态，捕获立刻被系统撤销，
 * AWT 根本收不到窗口外的鼠标事件。同一个原因还连带打掉了 Esc 与失焦关闭：
 * 弹层窗口（`javax.swing.Popup$HeavyWeightWindow`）出厂就是 `focusableWindowState=false`
 * 且 owner 就是那个锚点，于是 `panel.requestFocusInWindow()` 必失败，
 * `WHEN_IN_FOCUSED_WINDOW` 上的 Esc 绑定永不触发。
 *
 * 实测（JDK 17.0.20，把锚点从 JWindow 换成可聚焦 JDialog）：
 *   anchor.isFocusableWindow()            false → true
 *   popupWindow.isFocusableWindow()       false → true
 *   popupWindow windowGainedFocus         不来 → 真的来了
 *
 * 修复分三层，缺一不可：
 * 1. **锚点改成无边框 `JDialog` + `Type.POPUP` + `focusableWindowState = true`**（[invokerFor]）
 *    —— 走上面判定的 ② 直接为 true，JDK 的 grab 与焦点链恢复；
 * 2. **自管 `DismissController`**（[DismissController]）—— 全局 `AWTEventListener` 自己判
 *    「点在弹层矩形外 / 按了 Esc」就关。**这是主保障**：grab 是 AWT 内部行为，
 *    换个 JDK 或换个平台就可能又失效，那种时候用户只能杀进程，不能接受；
 * 3. **焦点 / 失活监听**（[TraySession.focusCloser]）—— 覆盖「点到其他应用 / 桌面 / 任务栏」
 *    这类 AWT 收不到鼠标事件的场景。
 *
 * ## 监听必须成对
 * `addAWTEventListener` / `removeAWTEventListener`、`addWindowFocusListener` /
 * `removeWindowFocusListener` 必须成对，而且**唯一摘除点是 [TraySession.handleClosed]**、加上
 * [TraySession.dispose] 的兜底 —— 否则菜单每次复用都会多挂一份全局监听，越用越卡。
 *
 * ## DPI：逻辑像素 + 整设备像素对齐
 * - 字号固定 **13pt 不乘倍率**：字体按 pt 走，JDK 已经把 DPI 算进去了，再乘一次等于放大两次
 *   （150% 屏上的 13pt 会被画成 18pt 的物理大小）。
 * - 行高 / 内边距 / 宽度用 `FontMetrics` 实测后 snap 到整设备像素，避免 125% / 150% 下出现
 *   半像素的行边界。
 * - 1px 边框与分隔线用 `1 / scale` 的逻辑线宽 —— 正好一个设备像素，任何倍率下都是发丝线。
 * - 每次弹出都重读一次 `GraphicsConfiguration.defaultTransform`，多屏混插（100% + 200%）时
 *   跟着托盘所在的那个屏走。
 *
 * ## 菜单创建一次、之后复用
 * `JPopupMenu` / `MenuPanel` / 1x1 锚点窗口长期持有，每次打开只**重新构建菜单项列表并重算尺寸**
 * （保证内容永远是最新的，即便某次 update 丢了）。复用要注意三点：
 * 弹层窗口可能被 PopupFactory 换一个；失焦 / 失活监听必须在关闭时成对 `remove*Listener`；
 * 自管的全局 `AWTEventListener` 也必须成对摘 —— 三者都随复用越积越多。
 * 统一摘除点是 [TraySession.handleClosed]；锚点窗口只在跨屏时才重建
 * （`GraphicsConfiguration` 决定弹层落在哪个屏）。
 */
object AppTray {
    /** 会话里的全部可变状态；AppTray 自己不存任何字段 */
    @Volatile
    private var session: TraySession? = null

    /**
     * 预热：把「全量字体枚举」与「图标解码」这两件重活提前做掉。
     *
     * 为什么必须单独有这一步：`install()` 是在 EDT 上跑的（见 [onEdt]），
     * 而它里面会碰到两件 Windows 上很慢的事：
     *  1. `GraphicsEnvironment.availableFontFamilyNames` —— 枚举系统全部字体，300ms~1.5s；
     *  2. `ImageIO.read` 的首次调用 —— 会做一次 ImageIO 的 SPI 扫描，50~150ms。
     * 这两件与首帧渲染挤在同一条 EDT 消息队列上，用户感知就是「双击后卡一下才出窗口」。
     *
     * 这里**不碰任何 AWT 组件**（不建 `TrayIcon` / `JPopupMenu` / 窗口），
     * 只把结果填进缓存，所以可以在 `main()` 里起一个守护线程跑，
     * 与 JVM / Compose 的初始化重叠掉；`install()` 落到 EDT 上时只剩轻量操作。
     *
     * macOS 例外：AWT 必须由主线程初始化（`CApplication` 绑在 AppKit 线程上），
     * 后台线程先碰到 `GraphicsEnvironment` 会把 AWT 初始化到错误的线程上，所以直接跳过。
     */
    fun warmUp() {
        if (PLATFORM == Platform.MACOS) return
        val started = System.currentTimeMillis()
        runCatching { installedFontFamilies() }
        runCatching { AppIcons.warmUp() }
        StartupTrace.span("tray.warmUp", started)
    }

    fun install(onEvent: (TrayEvent) -> Unit): Boolean {
        var installed = false
        // 所有托盘状态限定在 EDT：公开方法是唯一的切换点，内部不再各自 invokeAndWait
        onEdt {
            if (session != null) {
                // 幂等：已装好就复用，不重复注册监听、不加第二个 TrayIcon
                installed = true
                return@onEdt
            }
            if (!SystemTray.isSupported()) return@onEdt
            val image = loadTrayImage() ?: return@onEdt
            val created = TraySession(image, TrayState(), onEvent)
            if (!created.attach()) {
                created.dispose()
                return@onEdt
            }
            session = created
            installed = true
        }
        return installed
    }

    fun update(state: TrayState) {
        onEdt { session?.update(state) }
    }

    fun remove() {
        onEdt {
            session?.dispose()
            session = null
        }
    }
}

// ---------- 事件 / 状态 ----------

/**
 * 托盘菜单事件。「左键单击托盘图标」与「点菜单项」最终都汇到调用方的那一个 `when` 里。
 */
sealed interface TrayEvent {
    /** 显示主界面（窗口被隐藏时唤回来、被遮挡时提到前台） */
    data object ShowWindow : TrayEvent

    data object OpenSettings : TrayEvent

    data object About : TrayEvent

    data object Quit : TrayEvent
}

/**
 * 托盘要跟着变的那份状态，由 [AppTray.update] 唯一推送。
 *
 * @property dark 菜单配色的深浅（跟随应用主题的显式设置，或系统偏好）
 * @property tooltip 鼠标悬停时显示的运行状态摘要
 */
data class TrayState(
    val dark: Boolean = false,
    val tooltip: String = "Frpc-Android-Multi",
)

// ---------- 菜单项（唯一来源，单份定义） ----------

/** 一条菜单项：要么是能点的动作，要么是一条分隔线 */
private sealed interface MenuItem {
    data class Action(val text: String, val event: TrayEvent) : MenuItem

    data object Separator : MenuItem
}

private fun menuItems(): List<MenuItem> = listOf(
    MenuItem.Action("显示主界面", TrayEvent.ShowWindow),
    MenuItem.Action("设置", TrayEvent.OpenSettings),
    MenuItem.Action("关于", TrayEvent.About),
    MenuItem.Separator,
    MenuItem.Action("退出", TrayEvent.Quit),
)

// ---------- 会话：一个托盘周期变的全部东西 ----------

/** 同一次物理点击可能被 pressed / released 各投递一次 */
private const val TOGGLE_DEDUPE_MS = 50L
/** 自动关闭（外部点击 / 失焦）后多久内不允许由托盘点击重新打开 */
private const val AUTO_REOPEN_GUARD_MS = 250L
/**
 * 刚弹出的这一小段时间里，焦点会在「锚点 → 弹层 → 弹层里的面板」之间倒手
 * （实测锚点丢焦点就发生在弹层拿到焦点前 1ms）。这期间的丢焦 / 失活不算「用户点到外面」，
 * 否则菜单会刚出来就被自己关掉 —— 那比"点外面不关"还糟。
 */
private const val OPEN_GRACE_MS = 250L

/**
 * 一次 install 的全部状态：托盘图标、复用的菜单、各种「上一次」时间戳。
 * 构造（= install）时把监听一次注册完，之后只开 / 关，不再动监听。
 *
 * @param initialState 首次的深浅色与悬停提示；之后由 [update] 接管
 */
private class TraySession(
    private val image: Image,
    private val initialState: TrayState,
    private val onEvent: (TrayEvent) -> Unit,
) {
    private val palette = Palette(initialState.dark)
    private val panel = MenuPanel(palette, screenScale(null), ::onPicked)
    private val popup = JPopupMenu()
    /**
     * 弹层的锚点：1x1 全透明**无边框 JDialog**，放在菜单要出现的位置上。
     * 类型必须是「可聚焦的 Dialog」—— 详见文件头的根因分析。
     */
    private var anchor: JDialog? = null
    private var popupWindow: Window? = null
    private var icon: TrayIcon? = null
    private var opened = false
    private var nextCloseAuto = true
    private var lastToggleMs = 0L
    private var lastAutoCloseMs = 0L
    /** 本次弹出的时刻，供 [closeOnFocusLoss] 过滤掉弹出瞬间的焦点倒手 */
    private var openedAtMs = 0L

    /**
     * 自管的关闭控制器（第二层保险）。弹层矩形与关闭动作在这里注入，
     * 保证「全局监听成对摘除」这件事只有一个地方要操心。
     */
    private val dismiss = DismissController(
        boundsProvider = { popupBounds() },
        closer = { close(auto = true) },
    )

    /**
     * 挂在**弹层窗口**上的焦点 / 失活监听（第三层保险）。
     * 覆盖「点到其他应用 / 桌面 / 任务栏」—— 那种点击 AWT 收不到鼠标事件，
     * 只能靠弹层自己丢焦点 / 失活来发现。
     */
    private val focusCloser = object : WindowAdapter() {
        override fun windowLostFocus(e: WindowEvent?) = closeOnFocusLoss()
        override fun windowDeactivated(e: WindowEvent?) = closeOnFocusLoss()
    }

    /**
     * 挂在**锚点**上的失活监听。
     *
     * 注意这里**只听 windowDeactivated，不听 windowLostFocus**：菜单弹出的瞬间焦点是
     * 「锚点 → 弹层」，锚点会立刻丢一次焦点（实测 `anchor LOST focus` 就发生在
     * `popup gained focus` 前 1ms）—— 要是这里也关，菜单刚出来就被自己关掉了。
     */
    private val anchorCloser = object : WindowAdapter() {
        override fun windowDeactivated(e: WindowEvent?) = closeOnFocusLoss()
    }

    private val popupListener = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = Unit
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = handleClosed(nextCloseAuto)
        override fun popupMenuCanceled(e: PopupMenuEvent?) {
            nextCloseAuto = true
            handleClosed(auto = true)
        }
    }

    private val trayMouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            // macOS 的 popupTrigger 落在 pressed，普通点击与 Ctrl+点击都算
            if (PLATFORM == Platform.MACOS) toggleMenu()
            // Windows / Linux 的右键到 released 才是 popupTrigger，这里处理会「一次点击开两次菜单」
        }

        override fun mouseReleased(e: MouseEvent) {
            when (PLATFORM) {
                // macOS 已在 pressed 处理完，released 再动一次会变成「关掉又打开」
                Platform.MACOS -> Unit
                Platform.WINDOWS, Platform.LINUX, Platform.UNKNOWN -> when {
                    e.isPopupTrigger || e.button == MouseEvent.BUTTON3 -> toggleMenu()
                    e.button == MouseEvent.BUTTON1 -> dispatch(TrayEvent.ShowWindow)
                }
            }
        }
    }

    init {
        popup.apply {
            layout = BorderLayout()
            // 必须是重量级弹层：轻量弹层会把菜单画进 1x1 的锚点窗口里，屏幕上什么都看不到
            isLightWeightPopupEnabled = false
            border = BorderFactory.createEmptyBorder()
            background = palette.bg
            isOpaque = true
            add(panel, BorderLayout.CENTER)
            addPopupMenuListener(popupListener)
            // Windows LAF 没给 PopupMenu 配 Esc 绑定，自己挂一条（弹层窗口有焦点时生效）
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "tray.close")
            actionMap.put("tray.close", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = close(auto = true)
            })
        }
    }

    /** 挂上托盘图标；失败返回 false，调用方负责 dispose */
    fun attach(): Boolean {
        val created = TrayIcon(image, initialState.tooltip).apply {
            isImageAutoSize = true
            addMouseListener(trayMouse) // 只在 install 时注册一次
        }
        runCatching { SystemTray.getSystemTray().add(created) }
            .onFailure { return false }
        icon = created
        return true
    }

    /** 退出 / 重装的清理：关菜单 → 摘监听 → 释放锚点 → 摘图标 */
    fun dispose() {
        close(auto = false)
        // 菜单没开过的话 close() 会直接返回，这里补一刀：全局监听绝不能随 session 泄漏
        dismiss.detach()
        detachFocusCloser()
        runCatching { popup.removePopupMenuListener(popupListener) }
        runCatching { popup.removeAll() }
        val old = anchor
        anchor = null
        old?.let {
            runCatching { it.removeWindowListener(anchorCloser) }
            runCatching { it.isVisible = false }
            runCatching { it.dispose() }
        }
        val trayIcon = icon
        icon = null
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
    }

    /** 深浅色 + 悬停提示；菜单开着就立刻生效，关着就下次打开生效 */
    fun update(next: TrayState) {
        runCatching { icon?.toolTip = next.tooltip }
        palette.dark = next.dark
        if (!opened) return
        // 配色是每次绘制时从 palette 读的，重绘即可，不用把菜单关了重建
        popup.background = palette.bg
        panel.entries = menuItems()
        popup.repaint()
        panel.repaint()
    }

    // ---------- 开 / 关 ----------

    private fun toggleMenu() {
        val now = System.currentTimeMillis()
        if (now - lastToggleMs < TOGGLE_DEDUPE_MS) return
        lastToggleMs = now
        when {
            opened -> close(auto = false)
            // 刚刚是「点托盘 → 菜单自动收起」而关掉的：这次点击就是关菜单，不再打开
            now - lastAutoCloseMs > AUTO_REOPEN_GUARD_MS -> open()
        }
    }

    private fun open() {
        if (opened) return
        val placement = resolvePlacement()
        // 每次打开都重新构建条目并重新量算：内容永远是最新的，且跟着托盘所在的那个屏走
        panel.scale = placement.scale
        panel.entries = menuItems()
        val size = panel.measureSize()
        panel.preferredSize = size
        val invoker = invokerFor(placement.config)
        invoker.location = menuLocation(placement, size)
        // 先 remove 再 add：AWT 的 AWTEventMulticaster 一次只摘掉一个匹配项，
        // 万一某条路径漏了摘除，重复 add 会把同一个监听挂两份并越积越多
        runCatching { invoker.removeWindowListener(anchorCloser) }
        invoker.addWindowListener(anchorCloser)
        if (!invoker.isVisible) invoker.isVisible = true
        opened = true
        openedAtMs = System.currentTimeMillis()
        // show 每次新建弹层并按 JPopupMenu 的 preferredSize（= 上面量出来的尺寸）定大小，
        // 所以复用同一个 JPopupMenu 也不会残留上一次的尺寸
        popup.show(invoker, 0, 0) // 弹层左上角 = 锚点窗口位置
        // 锚点改成可聚焦 Dialog 之后，弹层窗口终于真的可聚焦了（实测 isFocusableWindow()
        // 从 false 变 true，windowGainedFocus 也真的会来）。补一次 focus，
        // 再把失焦 / 失活监听挂上去（同一个实例必须成对 add / remove）
        detachFocusCloser()
        popupWindow = SwingUtilities.getWindowAncestor(panel)
        popupWindow?.let {
            runCatching { it.removeWindowFocusListener(focusCloser) }
            runCatching { it.removeWindowListener(focusCloser) }
            it.addWindowFocusListener(focusCloser)
            it.addWindowListener(focusCloser)
        }
        // 兜底：show() 过程中若弹层已同步关闭，上面的 detach 会因 popupWindow 还没赋值而空转，
        // 这里再摘一次并直接返回 —— 绝不能给一个已经关掉的菜单挂上全局监听（会泄漏）
        if (!opened) {
            detachFocusCloser()
            return
        }
        // 自管的兜底放在最后：它要读弹层矩形，必须等弹层定位完成
        dismiss.attach()
        panel.resetInteraction()
        panel.requestFocusInWindow()
    }

    /**
     * 焦点 / 失活路径的统一入口：跳过弹出瞬间（[OPEN_GRACE_MS]）的焦点倒手，
     * 其余一律按「用户点到外面了」处理。
     */
    private fun closeOnFocusLoss() {
        if (System.currentTimeMillis() - openedAtMs < OPEN_GRACE_MS) return
        close(auto = true)
    }

    /** 弹层当前的屏幕矩形，供 [dismiss] 判断「点在里面还是外面」 */
    private fun popupBounds(): Rectangle? {
        val window = popupWindow ?: return null
        return runCatching {
            val at = window.locationOnScreen
            val size = window.size
            Rectangle(at.x - 2, at.y - 2, size.width + 4, size.height + 4)
        }.getOrNull()
    }

    fun close(auto: Boolean) {
        if (!opened) return
        nextCloseAuto = auto
        runCatching { popup.isVisible = false } // → popupMenuWillBecomeInvisible → handleClosed()
        handleClosed(nextCloseAuto) // 监听没触发时兜底
    }

    /**
     * **唯一的摘监听点**（[dispose] 里的那两行是同一语义的兜底）。
     * 全局 `AWTEventListener` 与窗口监听都必须在这里摘掉，否则菜单每开一次就多挂一份，
     * 反复开关之后 AWT 事件分发会越来越慢。
     */
    private fun handleClosed(auto: Boolean) {
        if (!opened) return
        opened = false
        if (auto) lastAutoCloseMs = System.currentTimeMillis()
        detachFocusCloser()
        dismiss.detach()
        runCatching { anchor?.isVisible = false }
        nextCloseAuto = true
    }

    private fun detachFocusCloser() {
        popupWindow?.let {
            runCatching { it.removeWindowFocusListener(focusCloser) }
            runCatching { it.removeWindowListener(focusCloser) }
        }
        popupWindow = null
        anchor?.let { runCatching { it.removeWindowListener(anchorCloser) } }
    }

    private fun onPicked(item: MenuItem.Action) {
        close(auto = false) // 先关菜单：否则「设置 / 关于」要弹的窗会被置顶的菜单压住
        dispatch(item.event)
    }

    private fun dispatch(event: TrayEvent) {
        onEvent(event)
    }

    // ---------- 位置 ----------

    /** 定位：先按任务栏那条边定向，再统一按可用区 clamp（放不下就翻转 / 内收） */
    private fun menuLocation(p: Placement, size: Dimension): Point {
        val gap = (4 * p.scale).roundToInt()
        val x = when (p.edge) {
            TaskbarEdge.LEFT -> p.anchor.x + p.iconSize.width + gap
            TaskbarEdge.RIGHT -> p.anchor.x - size.width - gap
            else -> p.anchor.x - size.width / 2
        }
        val y = when (p.edge) {
            TaskbarEdge.BOTTOM -> p.usable.y + p.usable.height - size.height
            TaskbarEdge.TOP -> p.usable.y
            else -> p.anchor.y - size.height / 2
        }
        return Point(
            x.coerceIn(p.usable.x, (p.usable.x + p.usable.width - size.width).coerceAtLeast(p.usable.x)),
            y.coerceIn(p.usable.y, (p.usable.y + p.usable.height - size.height).coerceAtLeast(p.usable.y)),
        )
    }

    /**
     * 弹层需要一个 invoker：1x1 全透明窗口，放在菜单要出现的位置上（见 [MenuPanel] 的说明）。
     * 长期复用，只有跨屏（`GraphicsConfiguration` 变了）才重建 —— 它决定弹层落在哪个屏。
     *
     * **必须是可聚焦的 `JDialog`**，原因见文件头「点击菜单外不关闭」那一节。要点重述：
     * - `focusableWindowState = true` 是硬要求：`Window.isFocusableWindow()` 第一步就否掉
     *   false 的窗口，JDK 的原生 grab 也就永远装不上；
     * - 必须是 Dialog（不是 Window / JWindow）：判定里 `this is Dialog` 直接返回 true，
     *   不依赖 owner 链，因而弹层窗口的 owner 链里也出现了 Dialog，弹层自己也能聚焦；
     * - `Type.POPUP` + `isUndecorated`：不进任务栏、没有边框，和原生菜单观感一致；
     * - owner 传 null：`JDialog` 会自己退化到 Swing 的 shared owner frame
     *   （一个从不显示的隐藏 Frame），省得我们再管一个隐藏窗口的生命周期。
     */
    private fun invokerFor(config: GraphicsConfiguration): JDialog {
        val current = anchor
        if (current != null) {
            val sameDevice = runCatching { current.graphicsConfiguration.device === config.device }
                .getOrDefault(false)
            if (sameDevice) return current
            runCatching { current.removeWindowListener(anchorCloser) }
            runCatching { current.dispose() }
            anchor = null
        }
        return JDialog(null as Frame?, "", false, config).apply {
            isUndecorated = true
            setSize(1, 1)
            background = Color(0, 0, 0, 0)
            // POPUP：不进任务栏（一个常驻后台的托盘程序不该在任务栏上多一个 tab）
            type = Window.Type.POPUP
            // 关键：改成 true 才能让 JDK 的 grab 与焦点链生效（旧实现这里是 false）
            focusableWindowState = true
        }.also { anchor = it }
    }
}

/**
 * 自管的「点外面 / 按 Esc 就关」控制器 —— 本轮修复的**主保障**。
 *
 * 为什么不只靠 JDK：JDK 那条路（原生 grab → `UngrabEvent` → `MouseGrabber` 取消弹层）
 * 依赖 AWT 的内部实现与平台行为，本轮的根因恰恰就是它没生效。一旦换 JDK / 换平台又失效，
 * 用户只能杀进程才能关掉菜单 —— 不能接受。所以这里用 `Toolkit` 的全局监听自己判一遍，
 * 逻辑完全在我们手里，行为可预期。
 *
 * 只暴露 [attach] / [detach]：弹层矩形与关闭动作由 [TraySession] 注入，
 * 于是「监听必须成对摘除」这条约定只集中在 `handleClosed()` 一处。
 *
 * @param boundsProvider 弹层当前的屏幕矩形；还没弹出来时返回 null（此时一律不关）
 * @param closer 关闭动作（由 [TraySession] 决定是 `close(auto = true)`）
 */
private class DismissController(
    private val boundsProvider: () -> Rectangle?,
    private val closer: () -> Unit,
) {
    @Volatile
    private var listener: AWTEventListener? = null

    /** 幂等：重复 attach 不会挂第二份监听 */
    fun attach() {
        if (listener != null) return
        val created = AWTEventListener { e -> onEvent(e) }
        val ok = runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                created,
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
            )
        }.isSuccess
        if (ok) listener = created
    }

    /** 幂等：唯一摘除点是 `TraySession.handleClosed()`（外加 dispose 的兜底） */
    fun detach() {
        val current = listener ?: return
        listener = null
        runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(current) }
    }

    private fun onEvent(e: AWTEvent) {
        when (e) {
            is MouseEvent -> if (e.id == MouseEvent.MOUSE_PRESSED) onPressed(e)
            is KeyEvent -> if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                closer()
            }
        }
    }

    private fun onPressed(e: MouseEvent) {
        // 托盘图标那一次点击没有 component（source 是 TrayIcon，不是窗口里的控件）：
        // 交给 toggleMenu 自己处理（有 250ms 的重开守卫），这里不能抢，否则会双关
        if (e.component == null) return
        val bounds = boundsProvider() ?: return
        if (bounds.contains(e.xOnScreen, e.yOnScreen)) return
        // 故意不 consume：原生菜单也是「关掉，但把这一次点击交给下面的东西」的语义，
        // 吞掉的话用户第一次点别处会像没点到
        closer()
    }
}

// ---------- 弹出位置 ----------

/** 任务栏所在的那条边：决定菜单往哪个方向弹 */
private enum class TaskbarEdge { TOP, BOTTOM, LEFT, RIGHT }

/** 一次弹出所需的屏幕信息；全部是**逻辑像素** */
private class Placement(
    val config: GraphicsConfiguration,
    val scale: Double,
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
    return Placement(gc, scale, anchor, usable, edge, iconSize)
}

// ---------- 菜单面板（全自绘） ----------

/** 菜单字号：**固定值**，DPI 由 JDK 处理（见文件头的 DPI 说明） */
private const val MENU_FONT_SIZE = 13

/**
 * 系统字体家族名单的缓存。
 *
 * `GraphicsEnvironment.availableFontFamilyNames` 在 Windows 上要 300ms~1.5s，
 * 只能枚举一次；由 `AppTray.warmUp()` 在后台线程预热，避免它落在 EDT 上。
 */
private val fontFamiliesLock = Any()

@Volatile
private var fontFamilies: Set<String>? = null

private fun installedFontFamilies(): Set<String> {
    fontFamilies?.let { return it }
    return synchronized(fontFamiliesLock) {
        fontFamilies ?: runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet()).also { fontFamilies = it }
    }
}

/**
 * 菜单字体：**中文渲染的唯一保障，不要动候选顺序**。
 *
 * 历史上出过「托盘菜单中文渲染成方框（□□）」的线上问题：AWT 原生菜单按系统「菜单字体」
 * 绘制且没有 CJK 回退，`MenuItem.setFont` 又不生效。所以这里必须自己挑一个能画中文的字体。
 */
private val MENU_FONT: Font by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val candidates = listOf(
        "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun",
        "Noto Sans CJK SC", "PingFang SC",
    )
    val installed = installedFontFamilies()
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

/**
 * 菜单内容画板：外壳是 `JPopupMenu`，这里是那个被塞进去的自绘 `JPanel`。
 * 只认 EDT：所有字段（含 [entries] / [scale]）都只在 EDT 上读写。
 */
private class MenuPanel(
    private val palette: Palette,
    scale: Double,
    private val onPick: (MenuItem.Action) -> Unit,
) : JPanel() {
    var entries: List<MenuItem> = emptyList()
        set(value) {
            field = value
            metrics = null
        }

    var scale: Double = scale
        set(value) {
            field = value
            metrics = null
        }

    private var metrics: Metrics? = null
    private var hover = -1
    private var pressed = -1

    init {
        isOpaque = true
        // **不要改成 false**：这是弹层能否聚焦的前提条件之一。
        // `PopupFactory` 只在内容组件可聚焦时，才把弹层窗口设成可聚焦
        // （它的 window name 会变成 `###focusableSwingPopup###`）。改成 false 之后
        // 弹层会悄悄退回不可聚焦，于是 Esc、失焦关闭、`requestFocusInWindow()` 全部失效
        // —— 也就是「点菜单外不关闭」那个 bug 原样复发，而且从代码上完全看不出来。
        // （QA 实测：换成不可聚焦的普通 JPanel，量出来的就是 isFocusableWindow=false）
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

    /** 复用会话时清掉上一次的交互残留（悬停 / 按下状态），否则重开会带着上一次的高亮块 */
    fun resetInteraction() {
        hover = -1
        pressed = -1
        repaint()
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
            y += if (entries[i] is MenuItem.Separator) sep else rowH
        }
        // 宽度按最长的一行文字算，上下左右留同等 padding
        val textW = entries.fold(0) { acc, item ->
            max(acc, if (item is MenuItem.Action) fm.stringWidth(item.text) else 0)
        }
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
        val right = snapDown(w - m.padX * 0.45f, scale)
        for (i in entries.indices) {
            when (val item = entries[i]) {
                MenuItem.Separator -> {
                    g2.color = palette.separator
                    g2.stroke = BasicStroke(hairline)
                    // 横线要落在「半个设备像素」上，1 设备像素的线宽才刚好铺满一整行
                    val ly = ((floor((m.tops[i] + m.sep / 2f) * scale) + 0.5) / scale).toFloat()
                    g2.draw(Line2D.Float(insetX, ly, w - insetX, ly))
                }

                is MenuItem.Action -> {
                    if (i == hover) {
                        g2.color = palette.selBg
                        g2.fill(Rectangle2D.Float(insetX, m.tops[i], right - insetX, m.rowH))
                    }
                    g2.font = MENU_FONT
                    g2.color = if (i == hover) palette.selFg else palette.fg
                    g2.drawString(item.text, m.padX, m.tops[i] + (m.rowH + m.fm.ascent - m.fm.descent) / 2f)
                }
            }
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
            val bottom = top + if (entries[i] is MenuItem.Separator) m.sep else m.rowH
            if (y >= top && y < bottom) return if (entries[i] is MenuItem.Action) i else -1
        }
        return -1
    }

    private fun setHover(index: Int) {
        if (hover == index) return
        hover = index
        repaint()
    }

    private fun pick(index: Int) {
        val item = entries.getOrNull(index)
        if (item is MenuItem.Action) onPick(item)
    }
}

// ---------- 平台 ----------

private enum class Platform { WINDOWS, MACOS, LINUX, UNKNOWN }

private val PLATFORM: Platform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("win") -> Platform.WINDOWS
        os.contains("mac") -> Platform.MACOS
        os.contains("nix") || os.contains("nux") || os.contains("aix") -> Platform.LINUX
        else -> Platform.UNKNOWN
    }
}

/**
 * 托盘图标：委托给 [AppIcons]。
 *
 * 「按平台 + 缩放挑最接近的档位」这套口径现在集中放在 `AppIcons`（它同时管窗口图标，
 * 档位表与 `GenTrayIcons.COLOR_SIZES` / `WINDOW_SIZES` 贴在一起，不会漂）。
 * 这里只保留托盘自己的行为判定 [PLATFORM]。
 */
private fun loadTrayImage(): Image? = AppIcons.trayImage()

// ---------- 公共小工具 ----------

/** 把逻辑像素向下对齐到整设备像素（画矩形边界用，避免半像素糊边） */
private fun snapDown(v: Float, scale: Double): Float = (floor(v * scale) / scale).toFloat()

/**
 * 逻辑像素 → 设备像素的倍率（100% = 1.0，150% = 1.5）；每次弹出都重读，支持多屏混插。
 *
 * `internal` 而不是 `private`：`AppIcons` 挑图标档位也要用同一套倍率口径，
 * 两边各写一份就会出现「托盘按 1.25 挑、窗口按 1.5 挑」这种不一致。
 */
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

/**
 * 菜单配色：不依赖 L&F 的暗色适配（Windows LAF 在暗色模式下经常画成黑底黑字）。
 * 颜色是 `get()` —— 主题切换时把 [dark] 一改，下一次重绘就是新配色，不必重建菜单。
 */
private class Palette(initialDark: Boolean) {
    var dark: Boolean = initialDark

    val bg: Color get() = if (dark) Color(0x2B, 0x2B, 0x2B) else Color(0xFF, 0xFF, 0xFF)
    val fg: Color get() = if (dark) Color(0xEE, 0xEE, 0xEE) else Color(0x1F, 0x1F, 0x1F)
    val selBg: Color get() = if (dark) Color(0x2F, 0x5A, 0x8C) else Color(0xE8, 0xF0, 0xFE)
    val selFg: Color get() = if (dark) Color(0xFF, 0xFF, 0xFF) else Color(0x1F, 0x1F, 0x1F)
    val border: Color get() = if (dark) Color(0x45, 0x45, 0x45) else Color(0xE0, 0xE0, 0xE0)
    val separator: Color get() = if (dark) Color(0x4A, 0x4A, 0x4A) else Color(0xE8, 0xE8, 0xE8)
}
