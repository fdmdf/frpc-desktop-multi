package com.frpc.desktop.core

import java.io.File

/**
 * 应用目录约定（全部在用户目录下，不写系统盘其他位置）：
 *   ~/.frpcdash/
 *     profiles.json   配置档案
 *     settings.json   应用设置
 *     bin/frpc.exe    运行时引擎（从安装包 resources/engine 解压）
 *     run/frpc.toml   当前进程实际使用的配置
 *     logs/           可选：导出日志
 */
object AppPaths {
    private const val HOME_DIR = ".frpcdash"
    /** 合并前的旧目录名，首次启动时自动迁移配置，避免升级后配置丢失 */
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
