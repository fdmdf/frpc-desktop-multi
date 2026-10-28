package com.frpc.desktop.ui

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 系统托盘。
 *
 * 菜单用 **Swing（JPopupMenu）而不是 AWT（PopupMenu）**：AWT 的菜单项虽然是 Java 侧设的字体，
 * 但 Windows 上实际由系统按菜单字体绘制，`setFont` 不生效 —— 中文会渲染成方框（□□）。
 * Swing 的菜单项是 Java 自己绘制的，组件字体一定生效，中文由 Java 的字体回退兜底（主界面同理）。
 *
 * 托盘图标本身仍必须是 AWT 的 `TrayIcon`（Swing 没有替代实现），只是弹出菜单换成 Swing 的。
 */
object AppTray {
    private var icon: TrayIcon? = null
    private var stopItem: JMenuItem? = null
    private var menu: JPopupMenu? = null
    /** 菜单的锚点窗口：Swing 弹出菜单需要一个可见的 invoker，用 1x1 全透明窗口充当 */
    private var anchor: JWindow? = null

    fun install(onShow: () -> Unit, onStop: () -> Unit, onQuit: () -> Unit): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val image = loadIcon() ?: return false
        val font = menuFont(12)

        onEdt {
            // 尽量贴近原生观感；失败就退回当前 L&F，不影响中文（字体是显式设的）
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            // 三重保险：组件字体 + L&F 默认值，任一条路径被用到都能拿到中文字体
            UIManager.put("PopupMenu.font", font)
            UIManager.put("MenuItem.font", font)

            val popup = JPopupMenu().apply { this.font = font }

            fun addItem(text: String, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
                this.font = font
                addActionListener { action() }
                popup.add(this)
            }

            addItem("显示窗口", onShow)
            stopItem = addItem("停止 frpc", onStop)
            popup.addSeparator()
            addItem("退出", onQuit)
            menu = popup

            anchor = JWindow().apply {
                setSize(1, 1)
                background = Color(0, 0, 0, 0) // 全透明，屏幕上只看得见菜单
                type = Window.Type.POPUP // 不进任务栏 / 不抢焦点
                isVisible = true
            }
        }

        val trayIcon = TrayIcon(image, "Frpc-Android-Multi").apply { isImageAutoSize = true }
        trayIcon.addActionListener { onShow() }
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

    fun updateRunning(running: Boolean) {
        onEdt { stopItem?.isEnabled = running }
    }

    fun remove() {
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        onEdt {
            menu?.isVisible = false
            anchor?.dispose()
        }
        icon = null
        stopItem = null
        menu = null
        anchor = null
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

    private fun loadIcon(): Image? = runCatching {
        AppTray::class.java.getResourceAsStream("/icon.png")?.use { ImageIO.read(it) }
    }.getOrNull()

    /** 在 EDT 上执行；install 常从 Compose 主线程调用，直接 invokeAndWait 会死锁 */
    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            runCatching { SwingUtilities.invokeAndWait(block) }
        }
    }
}
