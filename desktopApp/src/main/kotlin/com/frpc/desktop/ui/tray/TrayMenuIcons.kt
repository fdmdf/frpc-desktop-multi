package com.frpc.desktop.ui.tray

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D

internal enum class TrayMenuIcon { WINDOW, START, STOP, RESTART, GLOBE, FOLDER, DOC, EXIT }

internal object TrayMenuIcons {

    fun paint(g2: Graphics2D, icon: TrayMenuIcon, x: Int, y: Int, color: Color) {
        val g = g2.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.translate(x.toDouble(), y.toDouble())
        g.color = color
        g.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (icon) {
            TrayMenuIcon.WINDOW -> window(g)
            TrayMenuIcon.START -> start(g)
            TrayMenuIcon.STOP -> stop(g)
            TrayMenuIcon.RESTART -> restart(g)
            TrayMenuIcon.GLOBE -> globe(g)
            TrayMenuIcon.FOLDER -> folder(g)
            TrayMenuIcon.DOC -> doc(g)
            TrayMenuIcon.EXIT -> exit(g)
        }
        g.dispose()
    }

    private fun window(g: Graphics2D) {
        g.draw(RoundRectangle2D.Float(1.5f, 2.5f, 13f, 11f, 4f, 4f))
        g.draw(java.awt.geom.Line2D.Float(1.6f, 5.8f, 14.4f, 5.8f))
    }

    private fun start(g: Graphics2D) {
        val p = Path2D.Float().apply {
            moveTo(5.4f, 3.6f)
            lineTo(12.4f, 8f)
            lineTo(5.4f, 12.4f)
            closePath()
        }
        g.fill(p)
    }

    private fun stop(g: Graphics2D) {
        g.fill(RoundRectangle2D.Float(4.2f, 4.2f, 7.6f, 7.6f, 2.4f, 2.4f))
    }

    private fun restart(g: Graphics2D) {
        g.draw(Arc2D.Float(3f, 3f, 10f, 10f, 60f, -330f, Arc2D.OPEN))
        val p = Path2D.Float().apply {
            moveTo(6.4f, 1.5f)
            lineTo(6.4f, 4.5f)
            lineTo(9.6f, 3f)
            closePath()
        }
        g.fill(p)
    }

    private fun globe(g: Graphics2D) {
        g.draw(Ellipse2D.Float(2.5f, 2.5f, 11f, 11f))
        g.draw(Ellipse2D.Float(6.1f, 2.5f, 3.8f, 11f))
        g.draw(java.awt.geom.Line2D.Float(2.7f, 8f, 13.3f, 8f))
    }

    private fun folder(g: Graphics2D) {
        val p = Path2D.Float().apply {
            moveTo(1.8f, 12.6f)
            lineTo(1.8f, 4.4f)
            lineTo(6.2f, 4.4f)
            lineTo(7.5f, 6.1f)
            lineTo(14.2f, 6.1f)
            lineTo(14.2f, 12.6f)
            closePath()
        }
        g.draw(p)
    }

    private fun doc(g: Graphics2D) {
        g.draw(RoundRectangle2D.Float(3.2f, 2.2f, 9.6f, 11.6f, 2.6f, 2.6f))
        g.draw(java.awt.geom.Line2D.Float(5.6f, 6.2f, 10.4f, 6.2f))
        g.draw(java.awt.geom.Line2D.Float(5.6f, 8.6f, 10.4f, 8.6f))
        g.draw(java.awt.geom.Line2D.Float(5.6f, 11f, 8.6f, 11f))
    }

    private fun exit(g: Graphics2D) {
        g.draw(Arc2D.Float(3.1f, 3.1f, 9.8f, 9.8f, 110f, 320f, Arc2D.OPEN))
        g.draw(java.awt.geom.Line2D.Float(8f, 2.2f, 8f, 7.6f))
    }
}
