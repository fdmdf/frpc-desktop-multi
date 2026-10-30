package com.frpc.desktop.ui.tray

import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

data class TrayMenuState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val statusLine: String = "",
    val statusDot: Color = Color(0x5B6068),
    val adminPort: Int = 0,
    val version: String = "",
    val engineVersion: String = "",
    val dark: Boolean = false,
)

class TrayMenuActions(
    val show: () -> Unit = {},
    val toggleRun: () -> Unit = {},
    val restart: () -> Unit = {},
    val openStatusPage: (Int) -> Unit = {},
    val openLogs: () -> Unit = {},
    val openData: () -> Unit = {},
    val quit: () -> Unit = {},
)

internal object TrayFonts {
    private val family: String by lazy {
        val candidates = listOf(
            "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun",
            "Noto Sans CJK SC", "PingFang SC",
        )
        val installed = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        candidates.firstOrNull { it in installed } ?: Font.SANS_SERIF
    }

    fun of(size: Int): Font = Font(family, Font.PLAIN, size)
}

internal class TrayMenuWindow : JWindow() {

    private val translucent: Boolean = runCatching {
        graphicsConfiguration.device.isWindowTranslucencySupported(
            java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT,
        )
    }.getOrDefault(false)

    private val panel = MenuPanel()

    private var dismissWatcher: AWTEventListener? = null
    private var previousActive: Window? = null
    private var sawFocus = false

    init {
        background = if (translucent) Color(0, 0, 0, 0) else Color(0xFFFFFF)
        isAlwaysOnTop = true
        type = Window.Type.UTILITY
        focusableWindowState = true
        contentPane.layout = java.awt.BorderLayout()
        contentPane.add(panel)
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                sawFocus = true
            }

            override fun windowLostFocus(e: WindowEvent) {
                if (isVisible && sawFocus) hideMenu(restoreFocus = false)
            }
        })
        val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, "dismiss")
        rootPane.actionMap.put("dismiss", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = hideMenu(restoreFocus = true)
        })
    }

    private val inset: Int get() = if (translucent) TrayMenuMetrics.SHADOW_PAD else 0

    fun update(state: TrayMenuState, actions: TrayMenuActions) {
        panel.update(state, actions)
        if (isVisible) {
            setSize(panel.preferredSize)
            repaint()
        }
    }

    fun openAt(px: Int, py: Int) {
        previousActive = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        sawFocus = false
        setSize(panel.preferredSize)
        val size = this.size
        val gc = configurationAt(px, py)
        val work = workArea(gc)
        var x = if (px > work.x + work.width / 2) px - size.width + inset else px - inset
        var y = if (py > work.y + work.height / 2) py - size.height + inset else py - inset
        x = x.coerceIn(work.x, maxOf(work.x, work.x + work.width - size.width))
        y = y.coerceIn(work.y, maxOf(work.y, work.y + work.height - size.height))
        setLocation(x, y)
        isVisible = true
        toFront()
        requestFocus()
        panel.resetHover()
        startDismissWatcher()
    }

    fun hideMenu(restoreFocus: Boolean) {
        if (!isVisible) return
        stopDismissWatcher()
        isVisible = false
        panel.resetHover()
        if (restoreFocus) {
            val target = previousActive
            if (target != null && target !== this && target.isVisible) {
                target.toFront()
                target.requestFocus()
            }
        }
        previousActive = null
        sawFocus = false
    }

    fun hasFocusOrVisible(): Boolean = isVisible

    fun renderToImage(scale: Int, hoverIndex: Int, selectedIndex: Int): java.awt.image.BufferedImage {
        val size = panel.preferredSize
        val image = java.awt.image.BufferedImage(
            size.width * scale,
            size.height * scale,
            java.awt.image.BufferedImage.TYPE_INT_ARGB,
        )
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(scale.toDouble(), scale.toDouble())
        panel.setSize(size)
        panel.hoverIndex = hoverIndex
        panel.selectedIndex = selectedIndex
        panel.paint(g)
        g.dispose()
        return image
    }

    private fun startDismissWatcher() {
        if (dismissWatcher != null) return
        val listener = AWTEventListener { event ->
            if (event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
            val mouse = event as? MouseEvent ?: return@AWTEventListener
            if (!isVisible) return@AWTEventListener
            val point = mouse.locationOnScreen
            if (!bounds.contains(point)) hideMenu(restoreFocus = false)
        }
        dismissWatcher = listener
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        }
    }

    private fun stopDismissWatcher() {
        val listener = dismissWatcher ?: return
        dismissWatcher = null
        runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }

    private fun configurationAt(x: Int, y: Int): GraphicsConfiguration {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        return env.screenDevices
            .firstOrNull { it.defaultConfiguration.bounds.contains(x, y) }
            ?.defaultConfiguration
            ?: env.defaultScreenDevice.defaultConfiguration
    }

    private fun workArea(gc: GraphicsConfiguration): Rectangle {
        val bounds = gc.bounds
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }
            .getOrDefault(java.awt.Insets(0, 0, 0, 0))
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    private inner class MenuPanel : JPanel(null) {

        private var state = TrayMenuState()
        private var actions = TrayMenuActions()
        private var rows: List<Row> = emptyList()
        private var rowTops: IntArray = IntArray(0)
        private var labelFont: Font = TrayFonts.of(TrayMenuMetrics.FONT_SIZE)
        private var titleFont: Font = TrayFonts.of(TrayMenuMetrics.TITLE_FONT_SIZE)
        private var smallFont: Font = TrayFonts.of(TrayMenuMetrics.SMALL_FONT_SIZE)
        private var colors: TrayMenuColors = TrayMenuTheme.colors(false)

        var hoverIndex: Int = -1
        var selectedIndex: Int = -1

        init {
            isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val index = indexAt(e.point)
                    if (index >= 0 && item(index).enabled) {
                        pressedIndex = index
                        repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val index = indexAt(e.point)
                    val pressed = pressedIndex
                    pressedIndex = -1
                    if (index >= 0 && index == pressed && item(index).enabled) {
                        invoke(index)
                    }
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hoverIndex != -1) {
                        hoverIndex = -1
                        repaint()
                    }
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val index = indexAt(e.point)
                    val next = if (index >= 0 && item(index).enabled) index else -1
                    if (next != hoverIndex) {
                        hoverIndex = next
                        repaint()
                    }
                }
            })
        }

        private var pressedIndex = -1

        fun update(state: TrayMenuState, actions: TrayMenuActions) {
            this.state = state
            this.actions = actions
            this.colors = TrayMenuTheme.colors(state.dark)
            this.labelFont = TrayFonts.of(TrayMenuMetrics.FONT_SIZE)
            this.titleFont = TrayFonts.of(TrayMenuMetrics.TITLE_FONT_SIZE)
            this.smallFont = TrayFonts.of(TrayMenuMetrics.SMALL_FONT_SIZE)
            this.rows = buildRows(state, actions)
            val tops = IntArray(rows.size)
            var y = inset + TrayMenuMetrics.PANEL_PAD
            rows.forEachIndexed { index, row ->
                tops[index] = y
                y += heightOf(row)
            }
            rowTops = tops
            preferredSize = java.awt.Dimension(
                TrayMenuMetrics.PANEL_WIDTH + inset * 2,
                y + TrayMenuMetrics.PANEL_PAD + inset,
            )
            revalidate()
            repaint()
        }

        fun resetHover() {
            hoverIndex = -1
            selectedIndex = -1
            pressedIndex = -1
            repaint()
        }

        private fun heightOf(row: Row): Int = when (row) {
            is Row.Header -> TrayMenuMetrics.HEADER_HEIGHT
            is Row.Divider -> TrayMenuMetrics.DIVIDER_HEIGHT
            is Row.Item -> TrayMenuMetrics.ROW_HEIGHT
            is Row.Footer -> TrayMenuMetrics.FOOTER_HEIGHT
        }

        private fun item(index: Int): Row.Item = rows[index] as Row.Item

        private fun indexAt(point: java.awt.Point): Int {
            for (index in rows.indices) {
                val row = rows[index]
                if (row !is Row.Item) continue
                val top = rowTops[index]
                if (point.y >= top && point.y < top + TrayMenuMetrics.ROW_HEIGHT) {
                    val left = inset + TrayMenuMetrics.PANEL_PAD
                    val right = left + TrayMenuMetrics.PANEL_WIDTH - TrayMenuMetrics.PANEL_PAD * 2
                    if (point.x >= left && point.x <= right) return index
                }
            }
            return -1
        }

        private fun invoke(index: Int) {
            val action = item(index).action
            hideMenu(restoreFocus = true)
            runCatching { action() }
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val panelRect = RoundRectangle2D.Float(
                inset.toFloat(),
                inset.toFloat(),
                (TrayMenuMetrics.PANEL_WIDTH).toFloat(),
                (height - inset * 2).toFloat(),
                TrayMenuMetrics.PANEL_RADIUS.toFloat(),
                TrayMenuMetrics.PANEL_RADIUS.toFloat(),
            )

            if (translucent) {
                for (layer in 3 downTo 1) {
                    val grow = layer * 2f
                    g.color = Color(0, 0, 0, if (layer == 1) 10 else 6)
                    g.fill(
                        RoundRectangle2D.Float(
                            panelRect.x - grow,
                            panelRect.y - grow + 2f,
                            panelRect.width + grow * 2,
                            panelRect.height + grow * 2,
                            TrayMenuMetrics.PANEL_RADIUS + grow,
                            TrayMenuMetrics.PANEL_RADIUS + grow,
                        ),
                    )
                }
            }

            g.color = colors.surface
            g.fill(panelRect)
            g.color = colors.border
            g.stroke = BasicStroke(0.5f)
            g.draw(panelRect)

            rows.forEachIndexed { index, row ->
                val top = rowTops[index]
                when (row) {
                    is Row.Header -> paintHeader(g, row, top)
                    is Row.Divider -> paintDivider(g, top)
                    is Row.Item -> paintItem(g, row, index, top)
                    is Row.Footer -> paintFooter(g, row, top)
                }
            }
            g.dispose()
        }

        private fun paintHeader(g: Graphics2D, row: Row.Header, top: Int) {
            val left = inset + TrayMenuMetrics.PANEL_PAD + TrayMenuMetrics.ITEM_INSET
            g.font = titleFont
            g.color = colors.title
            g.drawString(row.title, left, top + 18)

            g.font = smallFont
            val small = g.fontMetrics
            val dotSize = 6
            val baseline = top + 17 + small.height - 1
            g.color = state.statusDot
            g.fillOval(left, baseline - small.ascent + (small.ascent - dotSize) / 2 + 1, dotSize, dotSize)
            g.color = colors.subtitle
            g.drawString(row.subtitle, left + dotSize + 6, baseline)
        }

        private fun paintDivider(g: Graphics2D, top: Int) {
            val left = inset + TrayMenuMetrics.PANEL_PAD + TrayMenuMetrics.ITEM_INSET
            val right = inset + TrayMenuMetrics.PANEL_WIDTH - TrayMenuMetrics.PANEL_PAD - TrayMenuMetrics.ITEM_INSET
            g.color = colors.divider
            g.stroke = BasicStroke(1f)
            val y = top + TrayMenuMetrics.DIVIDER_HEIGHT / 2
            g.draw(java.awt.geom.Line2D.Float(left.toFloat(), y.toFloat(), right.toFloat(), y.toFloat()))
        }

        private fun paintItem(g: Graphics2D, row: Row.Item, index: Int, top: Int) {
            val active = (index == hoverIndex || index == selectedIndex) && row.enabled
            val left = inset + TrayMenuMetrics.PANEL_PAD
            val width = TrayMenuMetrics.PANEL_WIDTH - TrayMenuMetrics.PANEL_PAD * 2
            if (active) {
                g.color = if (row.danger) colors.hoverDanger else colors.hover
                g.fill(
                    RoundRectangle2D.Float(
                        left.toFloat(),
                        top.toFloat(),
                        width.toFloat(),
                        TrayMenuMetrics.ROW_HEIGHT.toFloat(),
                        TrayMenuMetrics.ITEM_RADIUS.toFloat(),
                        TrayMenuMetrics.ITEM_RADIUS.toFloat(),
                    ),
                )
            }
            val iconX = left + TrayMenuMetrics.ITEM_INSET
            val iconY = top + (TrayMenuMetrics.ROW_HEIGHT - TrayMenuMetrics.ICON_SIZE) / 2
            val iconColor = when {
                !row.enabled -> colors.iconDisabled
                active && row.danger -> colors.hoverDangerText
                active -> colors.iconHover
                else -> colors.icon
            }
            TrayMenuIcons.paint(g, row.icon, iconX, iconY, iconColor)

            g.font = labelFont
            g.color = when {
                !row.enabled -> colors.textDisabled
                active && row.danger -> colors.hoverDangerText
                active -> colors.hoverText
                row.danger -> colors.dangerText
                else -> colors.text
            }
            val fm = g.fontMetrics
            g.drawString(
                row.label,
                iconX + TrayMenuMetrics.ICON_SIZE + TrayMenuMetrics.ICON_GAP,
                top + (TrayMenuMetrics.ROW_HEIGHT + fm.ascent - fm.descent) / 2,
            )
        }

        private fun paintFooter(g: Graphics2D, row: Row.Footer, top: Int) {
            g.font = smallFont
            g.color = colors.subtitle
            val fm = g.fontMetrics
            val left = inset + TrayMenuMetrics.PANEL_PAD + TrayMenuMetrics.ITEM_INSET
            g.drawString(row.text, left, top + (TrayMenuMetrics.FOOTER_HEIGHT + fm.ascent - fm.descent) / 2)
        }
    }

    private sealed interface Row {
        class Header(val title: String, val subtitle: String) : Row
        object Divider : Row
        class Item(
            val icon: TrayMenuIcon,
            val label: String,
            val enabled: Boolean,
            val danger: Boolean,
            val action: () -> Unit,
        ) : Row

        class Footer(val text: String) : Row
    }

    private fun buildRows(state: TrayMenuState, actions: TrayMenuActions): List<Row> {
        val rows = ArrayList<Row>(12)
        rows += Row.Header("Frpc-Android-Multi", state.statusLine)
        rows += Row.Item(TrayMenuIcon.WINDOW, "显示窗口", true, false, actions.show)
        rows += Row.Divider
        if (state.running) {
            rows += Row.Item(TrayMenuIcon.STOP, "停止 frpc", !state.busy, false, actions.toggleRun)
        } else {
            rows += Row.Item(TrayMenuIcon.START, "启动 frpc", !state.busy, false, actions.toggleRun)
        }
        rows += Row.Item(TrayMenuIcon.RESTART, "重启 frpc", state.running && !state.busy, false, actions.restart)
        rows += Row.Divider
        rows += Row.Item(
            TrayMenuIcon.GLOBE,
            "打开状态页",
            state.running && state.adminPort > 0,
            false,
        ) { actions.openStatusPage(state.adminPort) }
        rows += Row.Item(TrayMenuIcon.DOC, "打开日志目录", true, false, actions.openLogs)
        rows += Row.Item(TrayMenuIcon.FOLDER, "打开数据目录", true, false, actions.openData)
        rows += Row.Divider
        rows += Row.Item(TrayMenuIcon.EXIT, "退出", true, true, actions.quit)
        val footer = buildString {
            if (state.version.isNotBlank()) append("v").append(state.version)
            if (state.engineVersion.isNotBlank()) {
                if (isNotEmpty()) append("   ·   ")
                append("frp ").append(state.engineVersion)
            }
        }
        if (footer.isNotBlank()) rows += Row.Footer(footer)
        return rows
    }

    companion object {
        fun onEdt(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
        }
    }
}
