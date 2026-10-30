package com.frpc.desktop.ui

import com.frpc.desktop.ui.tray.TrayMenuActions
import com.frpc.desktop.ui.tray.TrayMenuState
import com.frpc.desktop.ui.tray.TrayMenuWindow
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

object AppTray {
    private val TRAY_SIZES = intArrayOf(16, 20, 22, 24, 32, 40, 48, 64, 128, 256)

    private var icon: TrayIcon? = null
    private var menu: TrayMenuWindow? = null
    private var state = TrayMenuState()
    private var actions = TrayMenuActions()

    fun install(handlers: TrayMenuActions): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        actions = handlers
        val image = loadIcon() ?: return false
        val trayIcon = TrayIcon(image, "Frpc-Android-Multi").apply { isImageAutoSize = true }
        trayIcon.addActionListener { handlers.show() }
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) toggleMenu()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) toggleMenu()
            }
        })
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
        true
    }.getOrDefault(false)

    fun updateState(value: TrayMenuState) {
        state = value
        onEdt { menu?.update(state, actions) }
    }

    fun updateTooltip(text: String) {
        runCatching { icon?.toolTip = text }
    }

    fun remove() {
        onEdt {
            menu?.let { window ->
                runCatching { window.hideMenu(restoreFocus = false) }
                runCatching { window.dispose() }
            }
            menu = null
        }
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    fun showMenuAt(x: Int, y: Int) {
        onEdt {
            val window = ensureMenu()
            if (window.hasFocusOrVisible()) {
                window.hideMenu(restoreFocus = false)
            } else {
                window.openAt(x, y)
            }
        }
    }

    fun renderMenuImage(
        target: TrayMenuState,
        handlers: TrayMenuActions,
        scale: Int,
        hoverIndex: Int,
        selectedIndex: Int,
    ): BufferedImage? = runCatching {
        var result: BufferedImage? = null
        val task = Runnable {
            val window = TrayMenuWindow()
            window.update(target, handlers)
            result = window.renderToImage(scale, hoverIndex, selectedIndex)
            runCatching { window.dispose() }
        }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        result
    }.getOrNull()

    private fun toggleMenu() {
        onEdt {
            val window = ensureMenu()
            if (window.hasFocusOrVisible()) {
                window.hideMenu(restoreFocus = false)
            } else {
                val point = runCatching { MouseInfo.getPointerInfo().location }.getOrNull() ?: return@onEdt
                window.openAt(point.x, point.y)
            }
        }
    }

    private fun ensureMenu(): TrayMenuWindow {
        menu?.let { existing ->
            existing.update(state, actions)
            return existing
        }
        val created = TrayMenuWindow()
        created.update(state, actions)
        menu = created
        return created
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
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
}
