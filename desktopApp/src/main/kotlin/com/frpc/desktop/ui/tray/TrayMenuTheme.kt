package com.frpc.desktop.ui.tray

import java.awt.Color

internal object TrayMenuMetrics {
    const val SHADOW_PAD = 8
    const val PANEL_PAD = 6
    const val PANEL_RADIUS = 10
    const val ROW_HEIGHT = 32
    const val ITEM_RADIUS = 6
    const val ITEM_INSET = 8
    const val ICON_SIZE = 16
    const val ICON_GAP = 10
    const val DIVIDER_HEIGHT = 15
    const val HEADER_HEIGHT = 44
    const val FOOTER_HEIGHT = 24
    const val PANEL_WIDTH = 212
    const val FONT_SIZE = 13
    const val TITLE_FONT_SIZE = 14
    const val SMALL_FONT_SIZE = 11
}

internal data class TrayMenuColors(
    val surface: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val text: Color,
    val textDisabled: Color,
    val hover: Color,
    val hoverText: Color,
    val hoverDanger: Color,
    val hoverDangerText: Color,
    val dangerText: Color,
    val divider: Color,
    val icon: Color,
    val iconDisabled: Color,
    val iconHover: Color,
    val shadow: Color,
)

internal object TrayMenuTheme {

    private val lightColors = TrayMenuColors(
        surface = Color(0xFFFFFF),
        border = Color(0xE4E7EC),
        title = Color(0x1A1D21),
        subtitle = Color(0x6B7280),
        text = Color(0x1A1D21),
        textDisabled = Color(0xA9B0B9),
        hover = Color(0xEDF2FD),
        hoverText = Color(0x0B57D0),
        hoverDanger = Color(0xFDECEC),
        hoverDangerText = Color(0xB3261E),
        dangerText = Color(0x1A1D21),
        divider = Color(0xE9ECF1),
        icon = Color(0x6B7280),
        iconDisabled = Color(0xC7CCD4),
        iconHover = Color(0x0B57D0),
        shadow = Color(0x000000),
    )

    private val darkColors = TrayMenuColors(
        surface = Color(0x23262B),
        border = Color(0x31353C),
        title = Color(0xE6E8EB),
        subtitle = Color(0x9AA1AB),
        text = Color(0xE6E8EB),
        textDisabled = Color(0x757C86),
        hover = Color(0x2E3238),
        hoverText = Color(0x9EC1FF),
        hoverDanger = Color(0x3A2626),
        hoverDangerText = Color(0xF09595),
        dangerText = Color(0xE6E8EB),
        divider = Color(0x33373D),
        icon = Color(0xA8B0BA),
        iconDisabled = Color(0x4A4F57),
        iconHover = Color(0x9EC1FF),
        shadow = Color(0x000000),
    )

    fun colors(dark: Boolean): TrayMenuColors = if (dark) darkColors else lightColors
}
