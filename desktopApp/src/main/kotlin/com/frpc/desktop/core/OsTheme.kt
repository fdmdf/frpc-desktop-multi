package com.frpc.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 探测**系统**当前是不是深色模式（应用主题设为「跟随系统」时才需要）。
 *
 * Windows L&F 并不会可靠地跟随系统深色模式（常见症状是菜单黑底黑字），
 * 所以托盘菜单的配色由我们自己决定 —— 这就需要一个可靠的深浅色判断。
 *
 * ## 为什么拆成「缓存读」+「异步探测」
 * Windows 的探测要 `ProcessBuilder("reg", "query", ...)` + `waitFor`，
 * 创建进程 + `reg.exe` 冷启动通常 150~600ms（杀软介入可能到 1s）。
 * 原来的 `prefersDark()` 是同步的，而调用点在 `Main.kt` 的 `remember {}` 里 ——
 * 也就是 **EDT 上**，而且排在 `Window(...)` 之前，等于每次冷启动都先卡住窗口创建。
 *
 * 现在的用法：
 * - 首帧走 [prefersDarkCached]，拿不到就按浅色先渲染（绝不阻塞）；
 * - 同时由 [prefersDarkAsync] 在 `Dispatchers.IO` 上探测一次，结果回来再刷新配色。
 *
 * 只 fork 一次：探测结果写进 [cached]，之后的调用全是缓存命中。
 * Linux / 未知平台只读环境变量（不 fork），所以 [prefersDarkCached] 能立刻给出答案。
 */
object OsTheme {

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var probeStarted = false

    /** 需要 fork 外部命令的平台；只有这些才值得异步（Linux 读环境变量是瞬时的） */
    private fun needsProcess(): Boolean = isWindows() || isMac()

    /**
     * 首帧用：能立刻给出的就给，需要 fork 的返回 `null`（调用方按浅色渲染，别阻塞）。
     * 返回 `null` 不代表"探测过且是浅色"，只是"还没探测出来"。
     */
    fun prefersDarkCached(): Boolean? {
        cached?.let { return it }
        if (needsProcess()) return null
        val value = linuxDark()
        cached = value
        return value
    }

    /**
     * 在后台探测一次并把结果写进缓存；完成后在**主线程**回调 [onReady]。
     *
     * 幂等：只会 fork 一次（[probeStarted] 闸门），重复调用不会多起进程。
     * 不需要 fork 的平台（Linux）直接同步回调，连协程都不用起。
     */
    fun prefersDarkAsync(scope: CoroutineScope, onReady: (Boolean) -> Unit) {
        if (!needsProcess()) {
            val value = prefersDarkCached() ?: false
            onReady(value)
            return
        }
        cached?.let { onReady(it); return }
        if (probeStarted) return
        probeStarted = true
        scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val value = runCatching { probe() }.getOrDefault(false)
            cached = value
            StartupTrace.span("osTheme.probe", started)
            withContext(Dispatchers.Main) { onReady(value) }
        }
    }

    /** 同步探测（会 fork 一次外部命令）：只在 IO 线程 / 单元测试里直接调用 */
    fun probe(): Boolean {
        cached?.let { return it }
        val value = runCatching {
            when {
                isWindows() -> windowsDark()
                isMac() -> macDark()
                else -> linuxDark()
            }
        }.getOrDefault(false)
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
