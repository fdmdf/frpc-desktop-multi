package com.frpc.desktop.core

/** 应用版本：构建时由 Gradle 写入 resources/VERSION（与 jpackage 的 packageVersion 同源） */
object AppInfo {
    val version: String by lazy {
        runCatching {
            AppInfo::class.java.getResourceAsStream("/VERSION")
                ?.bufferedReader()?.use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
    }
}
