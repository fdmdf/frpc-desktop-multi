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
import kotlin.math.abs
import kotlin.math.roundToInt

object AppTray {
    private val TRAY_SIZES = intArrayOf(16, 20, 22, 24, 32, 40, 48, 64, 128, 256)

    private var icon: TrayIcon? = null
    private var stopItem: JMenuItem? = null
    private var menu: JPopupMenu? = null
    private var anchor: JWindow? = null

    fun install(onShow: () -> Unit, onStop: () -> Unit, onQuit: () -> Unit): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val image = loadIcon() ?: return false
        val font = menuFont(12)

        onEdt {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
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
                background = Color(0, 0, 0, 0)
                type = Window.Type.POPUP
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

    fun showAtMouse() {
        val p = runCatching { MouseInfo.getPointerInfo().location }.getOrNull() ?: return
        showMenuAt(p.x, p.y)
    }

    fun showMenuAt(x: Int, y: Int) {
        onEdt {
            val m = menu ?: return@onEdt
            val a = anchor ?: return@onEdt
            a.setLocation(x, y)
            m.show(a, 0, 0)
        }
    }

    internal fun popupForTest(): JPopupMenu? = menu

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
        val stream = AppTray::class.java.getResourceAsStream(trayIconResource())
            ?: AppTray::class.java.getResourceAsStream("/icon.png")
        stream?.use { ImageIO.read(it) }
    }.getOrNull()

    private fun trayIconResource(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val scale = screenScale()
        if (os.contains("mac")) {
            return if (scale >= 1.5) "/tray/tray-mac@2x.png" else "/tray/tray-mac.png"
        }
        val base = if (os.contains("win")) 16 else 22
        val target = (base * scale).roundToInt().coerceIn(16, 256)
        val best = TRAY_SIZES.minByOrNull { abs(it - target) } ?: 32
        return "/tray/tray-$best.png"
    }

    private fun screenScale(): Double = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    }.getOrDefault(1.0)

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            runCatching { SwingUtilities.invokeAndWait(block) }
        }
    }
}
