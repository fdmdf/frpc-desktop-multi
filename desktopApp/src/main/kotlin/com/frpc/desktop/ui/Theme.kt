package com.frpc.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StatusRunning = Color(0xFF2E7D32)
val StatusError = Color(0xFFB3261E)
val StatusIdle = Color(0xFF5B6068)
val StatusStarting = Color(0xFFB26A00)

private val Brand = Color(0xFF0B57D0)
private val BrandDark = Color(0xFF9EC1FF)

@Composable
fun AppTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = BrandDark,
            secondary = BrandDark,
            background = Color(0xFF141518),
            surface = Color(0xFF1B1D21),
            onSurfaceVariant = Color(0xFFB4B9C2),
        )
    } else {
        lightColorScheme(
            primary = Brand,
            secondary = Brand,
            background = Color(0xFFF6F7FA),
            surface = Color(0xFFFFFFFF),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
