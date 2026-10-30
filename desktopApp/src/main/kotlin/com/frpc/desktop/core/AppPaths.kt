package com.frpc.desktop.core

import java.io.File

object AppPaths {
    private const val HOME_DIR = ".frpcdash"
    private const val LEGACY_HOME_DIR = ".frpc-desktop"

    val home: File = resolveHome()

    val profilesFile: File = File(home, "profiles.json")
    val settingsFile: File = File(home, "settings.json")
    val binDir: File = File(home, "bin").apply { mkdirs() }
    val runDir: File = File(home, "run").apply { mkdirs() }
    val logsDir: File = File(home, "logs").apply { mkdirs() }

    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")
    val engineFileName: String = if (isWindows) "frpc.exe" else "frpc"
    val runConfigFile: File = File(runDir, "frpc.toml")

    private fun resolveHome(): File {
        val userHome = File(System.getProperty("user.home"))
        val current = File(userHome, HOME_DIR)
        current.mkdirs()
        if (current.exists()) {
            val legacy = File(userHome, LEGACY_HOME_DIR)
            if (!File(current, "profiles.json").exists() && legacy.isDirectory) {
                runCatching {
                    listOf("profiles.json", "settings.json").forEach { name ->
                        val source = File(legacy, name)
                        if (source.isFile) source.copyTo(File(current, name), overwrite = false)
                    }
                }
            }
        }
        return current
    }
}
