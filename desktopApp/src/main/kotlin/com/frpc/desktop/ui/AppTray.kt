package com.frpc.desktop.ui

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * 系统托盘（AWT 实现）。
 *
 * 不用 Compose 的 `Tray { }` 是因为它不暴露菜单项字体：AWT 的 MenuItem 未显式设字体时
 * 用的是系统菜单默认字体，在中文 Windows 上不含 CJK 字形，中文菜单项会渲染成方框（□□□）。
 */
object AppTray {
    private var icon: TrayIcon? = null
    private var stopItem: MenuItem? = null
    private var menu: PopupMenu? = null

    fun install(onShow: () -> Unit, onStop: () -> Unit, onQuit: () -> Unit): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val image = loadIcon() ?: return false
        val font = cjkFont(12)
        val popup = PopupMenu().apply { this.font = font }

        fun addItem(text: String, action: () -> Unit): MenuItem = MenuItem(text).apply {
            this.font = font
            addActionListener { action() }
            popup.add(this)
        }

        addItem("显示窗口", onShow)
        stopItem = addItem("停止 frpc", onStop)
        popup.addSeparator()
        addItem("退出", onQuit)

        val trayIcon = TrayIcon(image, "frpc-desktop-multi", popup).apply { isImageAutoSize = true }
        trayIcon.addActionListener { onShow() }
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
        menu = popup
        true
    }.getOrDefault(false)

    fun updateTooltip(text: String) {
        runCatching { icon?.toolTip = text }
    }

    fun updateRunning(running: Boolean) {
        runCatching { stopItem?.isEnabled = running }
    }

    fun remove() {
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        icon = null
        stopItem = null
    }

    /** 仅供 --tray-test 自检：把菜单弹到屏幕上核对字体 */
    internal fun popupForTest(): PopupMenu? = menu

    /** 优先选已安装的中文族名；最后兜底 AWT 逻辑字体 Dialog（Windows 上带 CJK 回退） */
    private fun cjkFont(size: Int): Font {
        val candidates = listOf(
            "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun",
            "Noto Sans CJK SC", "PingFang SC", "Dialog",
        )
        val installed = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        val hit = candidates.firstOrNull { it in installed && Font(it, Font.PLAIN, size).canDisplay(PROBE) }
        return Font(hit ?: Font.DIALOG, Font.PLAIN, size)
    }

    private fun loadIcon(): Image? = runCatching {
        AppTray::class.java.getResourceAsStream("/icon.png")?.use { ImageIO.read(it) }
    }.getOrNull()

    private const val PROBE = '\u663E'
}
