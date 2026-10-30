package com.frpc.desktop.core

object AppInfo {
    val version: String by lazy {
        runCatching {
            AppInfo::class.java.getResourceAsStream("/VERSION")
                ?.bufferedReader()?.use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
    }
}
