package com.frpc.desktop.core

import java.util.concurrent.TimeUnit

/**
 * 探测**系统**当前是不是深色模式（应用主题设为「跟随系统」时才需要）。
 *
 * Windows L&F 并不会可靠地跟随系统深色模式（常见症状是菜单黑底黑字），
 * 所以托盘菜单的配色由我们自己决定 —— 这就需要一个可靠的深浅色判断。
 *
 * 探测结果只缓存一次：短时间内系统主题不会变，而托盘菜单每次构建都要读这个值，
 * 每次都 fork 一个进程太浪费。
 */
object OsTheme {
    private var cached: Boolean? = null

    fun prefersDark(): Boolean {
        cached?.let { return it }
        val value = when {
            isWindows() -> windowsDark()
            isMac() -> macDark()
            else -> linuxDark()
        }
        cached = value
        return value
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** HKCU\...\Themes\Personalize\AppsUseLightTheme = 0 表示深色 */
    private fun windowsDark(): Boolean {
        val out = exec(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ) ?: return false
        val m = Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x([0-9a-fA-F]+)""").find(out)
        return m?.groupValues?.get(1)?.toIntOrNull(16) == 0
    }

    /** `defaults read -g AppleInterfaceStyle` 在深色下输出 "Dark"，浅色下报错退出 */
    private fun macDark(): Boolean {
        val out = exec("defaults", "read", "-g", "AppleInterfaceStyle") ?: return false
        return out.trim().equals("Dark", ignoreCase = true)
    }

    /** Linux 没有统一接口：只看 GTK_THEME / 桌面环境变量里有没有 dark，拿不准就当浅色 */
    private fun linuxDark(): Boolean {
        val gtk = System.getenv("GTK_THEME").orEmpty()
        if (gtk.contains("dark", ignoreCase = true)) return true
        val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
        return desktop.contains("dark", ignoreCase = true)
    }

    /** 跑一条外部命令并取标准输出；失败/超时一律返回 null（探测失败按浅色处理） */
    private fun exec(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        p.inputStream.bufferedReader().readText()
    }.getOrNull()
}
