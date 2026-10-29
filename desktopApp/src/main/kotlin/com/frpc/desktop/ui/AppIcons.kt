package com.frpc.desktop.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Image
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 运行时图标的**唯一入口**：窗口 / 任务栏 / 托盘三处的图标都从这里取。
 *
 * 背景（本轮修的两个现象）：
 * - 以前窗口只给一张 512 的 PNG，由 AWT 缩到 16/20/24 —— 标题栏与任务栏发虚，
 *   而且托盘走的另一套（16px 单独画的简化图），两边看着不像一个东西。
 * - 每张图都是「重组时现解码」，`app()` 订阅了 6 个 Flow（status 4s / metrics 2s），
 *   于是稳态也在反复解码 PNG 并丢出新对象。
 *
 * 现在的约定：
 * 1. **文件由 `scripts/gen-tray-icons/GenTrayIcons.java` 生成**，改图标一律改那份 Java
 *    （它有 `--sync-svg` 把 `desktopApp/icon.svg` 反向同步，两边不可能漂）；
 * 2. **这里只做「按档位取名 + 解码 + 缓存」**，解码一次之后全是命中；
 * 3. 下面的 `WINDOW_SIZES` / `COLOR_SIZES` 必须和生成器的同名数组一致，改一边要同步另一边。
 *
 * 线程：所有字段 `@Volatile`、写都在 [lock] 内，方法可从任意线程调用。
 * 冷启动优化（T02）会在后台线程先跑一次 [warmUp]，避免首次 `ImageIO.read` 的
 * SPI 扫描落在 EDT 上和首帧抢时间。
 */
object AppIcons {

    /**
     * 窗口 / 任务栏的尺寸档位 —— 与 `GenTrayIcons.WINDOW_SIZES` 一致。
     *
     * 为什么要多档：AWT 只有在 `Window.iconImages` 拿到**多尺寸列表**时才会按场景挑原图，
     * 只给一张就被迫缩放（这就是标题栏/任务栏发虚的原因）。16/20/24/32 覆盖标题栏与
     * 任务栏，40/48/64 覆盖 Alt-Tab 与高 DPI，128/256/512 覆盖资源管理器与缩略图。
     */
    private val WINDOW_SIZES = intArrayOf(16, 20, 24, 32, 40, 48, 64, 128, 256, 512)

    /**
     * 彩色托盘图标的档位 —— 与 `GenTrayIcons.COLOR_SIZES` 一致
     * （旧的 `AppTray.TRAY_SIZES` 已并入这里，托盘不再自己记一份）。
     */
    private val COLOR_SIZES = intArrayOf(16, 20, 22, 24, 32, 40, 48, 64, 128, 256)

    /** 托盘基准尺寸：Windows 任务栏 16px@100%，Linux 常见 22/24 */
    private const val TRAY_BASE_WINDOWS = 16
    private const val TRAY_BASE_OTHER = 22

    private val lock = Any()

    @Volatile
    private var windowImages: List<Image>? = null

    @Volatile
    private var windowPainterRef: Painter? = null

    /** 资源路径 → 已解码的图；值是 nullable（资源缺失要缓存"没有"这个结论，别每次重读） */
    private val cache = HashMap<String, Image?>()

    /**
     * 交给 AWT 的多尺寸窗口图标。**AWT 只有拿到这个列表才会按场景选原图** ——
     * 这是「任务栏 / 标题栏不发虚」的关键一步（另见 [windowPainter]）。
     */
    fun windowIconImages(): List<Image> {
        windowImages?.let { return it }
        return synchronized(lock) {
            windowImages ?: WINDOW_SIZES.asSequence()
                .mapNotNull { n -> image("/icon/icon-$n.png") }
                .toList()
                .also { windowImages = it }
        }
    }

    /**
     * Compose 侧要的窗口图标（[androidx.compose.ui.window.Window] 的 `icon` 参数）。
     * 同样只按当前 DPI 挑最接近的一档，不给 512 让它缩放。
     */
    fun windowPainter(): Painter? {
        windowPainterRef?.let { return it }
        return synchronized(lock) {
            windowPainterRef ?: runCatching {
                // 标题栏图标的物理大小约 16~20px @100%，按 32 作基准挑档，150% 时自然升到 48
                val target = (32.0 * screenScale(null)).roundToInt()
                val tier = pickTier(WINDOW_SIZES, target)
                val bytes = bytesOf("/icon/icon-$tier.png")
                    ?: bytesOf("/icon.png")
                    ?: return@runCatching null
                BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
            }.getOrNull().also { windowPainterRef = it }
        }
    }

    /**
     * 托盘图标：Windows / Linux 取彩色档位，macOS 取模板图（系统按深浅菜单栏自动反色）。
     *
     * 为什么「选图」放在这里而不在 `AppTray`：`tray-<n>.png` 的命名与档位表是
     * 生成器的产物约定，和托盘的点击行为无关，放一起才不会漂。
     * 注意托盘的**点击行为**判定另见 `AppTray.PLATFORM`（两边都是 `os.name` 三分支）。
     */
    fun trayImage(): Image? {
        return if (isMac()) {
            val retina = screenScale(null) >= 1.5
            image(if (retina) "/tray/tray-mac@2x.png" else "/tray/tray-mac.png")
        } else {
            val base = if (isWindows()) TRAY_BASE_WINDOWS else TRAY_BASE_OTHER
            val target = (base * screenScale(null)).roundToInt().coerceIn(16, 256)
            image("/tray/tray-${pickTier(COLOR_SIZES, target)}.png")
        } ?: image("/icon.png") // 兜底：512 那张一直存在（也是 linux 的 jpackage iconFile）
    }

    /**
     * 预热：把窗口与托盘图标提前解码并缓存好。
     *
     * 由 `AppTray.warmUp()` 在后台线程调用。首次 `ImageIO.read` 会做一次 ImageIO 的
     * SPI 扫描（遍历所有 `javax.imageio.spi.*` 的 ServiceLoader），几十到上百毫秒，
     * 放进 EDT 就会和首帧抢时间。
     */
    fun warmUp() {
        windowIconImages()
        runCatching { trayImage() }
        runCatching { windowPainter() }
    }

    /** 按资源路径读一张图（带缓存；缺失也缓存，避免反复走失败的 IO） */
    private fun image(resource: String): Image? = synchronized(lock) {
        if (cache.containsKey(resource)) return@synchronized cache[resource]
        val loaded = runCatching {
            AppIcons::class.java.getResourceAsStream(resource)?.use { ImageIO.read(it) }
        }.getOrNull()
        cache[resource] = loaded
        loaded
    }

    private fun bytesOf(resource: String): ByteArray? = runCatching {
        AppIcons::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
    }.getOrNull()

    /** 在给定的档位表里挑最接近 target 的一档（表非空，取不到时用第一档） */
    private fun pickTier(tiers: IntArray, target: Int): Int =
        tiers.minByOrNull { abs(it - target) } ?: tiers.first()

    private fun osContains(token: String): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains(token)

    private fun isWindows(): Boolean = osContains("win")
    private fun isMac(): Boolean = osContains("mac")
}
