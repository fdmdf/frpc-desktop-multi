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

    init {
        runCatching { JPopupMenu.setDefaultLightWeightPopupEnabled(false) }
    }

    private class Actions(val show: () -> Unit, val stop: () -> Unit, val quit: () -> Unit)

    private var icon: TrayIcon? = null
    private var stopItem: JMenuItem? = null
    private var menu: JPopupMenu? = null
    private var anchor: JWindow? = null
    private var actions: Actions? = null
    private var running = false

    fun install(onShow: () -> Unit, onStop: () -> Unit, onQuit: () -> Unit): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        actions = Actions(onShow, onStop, onQuit)
        val image = loadIcon() ?: return false
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

    fun updateRunning(value: Boolean) {
        running = value
        onEdt { stopItem?.isEnabled = value }
    }

    fun remove() {
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        val popup = menu
        val holder = anchor
        if (popup != null || holder != null) {
            onEdt {
                runCatching { popup?.isVisible = false }
                runCatching { holder?.dispose() }
            }
        }
        icon = null
        stopItem = null
        menu = null
        anchor = null
        actions = null
    }

    fun showAtMouse() {
        val p = runCatching { MouseInfo.getPointerInfo().location }.getOrNull() ?: return
        showMenuAt(p.x, p.y)
    }

    fun showMenuAt(x: Int, y: Int) {
        onEdt {
            val popup = ensureMenu() ?: return@onEdt
            val holder = anchor ?: return@onEdt
            holder.setLocation(x, y)
            popup.show(holder, 0, 0)
        }
    }

    internal fun popupForTest(): JPopupMenu? = runCatching { ensureMenu() }.getOrNull()

    private fun ensureMenu(): JPopupMenu? {
        menu?.let { return it }
        val act = actions ?: return null
        val font = menuFont(12)
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        UIManager.put("PopupMenu.font", font)
        UIManager.put("MenuItem.font", font)
        val popup = JPopupMenu().apply { this.font = font }

        fun addItem(text: String, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
            this.font = font
            addActionListener { action() }
            popup.add(this)
        }

        addItem("显示窗口", act.show)
        stopItem = addItem("停止 frpc", act.stop).apply { isEnabled = running }
        popup.addSeparator()
        addItem("退出", act.quit)
        anchor = JWindow().apply {
            setSize(1, 1)
            background = Color(0, 0, 0, 0)
            type = Window.Type.POPUP
            isVisible = true
        }
        menu = popup
        return popup
    }

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
            runCatching { SwingUtilities.invokeLater(block) }
        }
    }
}
