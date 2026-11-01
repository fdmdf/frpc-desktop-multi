package io.github.fdmdf.frpc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Rust 侧通过 JNI 调用的宿主能力：打开外部链接、申请电池优化白名单。
 *
 * 为什么要 Kotlin 中转：`startActivity` 需要 Intent/Uri/PendingIntent 这些
 * Java 对象，从 Rust 里拼 JNI 调用又长又容易写错（每个构造器签名手写）。
 * 这里用 `@JvmStatic` 暴露成 `(Landroid/content/Context;...)` 的静态方法，
 * Rust 侧只需要 `call_static_method` 一行。
 *
 * 两条约束（v1.4.8 上「允许后台运行」一点就报 Java 异常，就是没守住它们）：
 *
 * 1. **绝不向外抛异常**。JNI 调用里漏出的异常会挂在线程上，把后续所有 JNI 调用
 *    （含 Tauri 回传结果的 IPC）一起弄炸，前端只能看到
 *    「postMessage: Java exception was raised」，真正原因全被吞掉。
 *    所以这里每个入口都 try/catch(Throwable)，失败返回安全默认值。
 * 2. **startActivity / Toast 一律回到主线程**。Rust 的命令跑在 tokio 线程上，
 *    非主线程调 Toast 会直接抛 `Can't create handler inside thread that has not
 *    called Looper.prepare()`；startActivity 在部分 ROM 上也有类似限制。
 */
object FrpcHost {

    private const val TAG = "FrpcHost"

    /** 主线程 Handler：Rust 侧调用进来时不在主线程。 */
    private val main = Handler(Looper.getMainLooper())

    /**
     * 当前 Activity 的弱引用。
     *
     * Rust 侧只能拿到 `ndk_context` 里的 Context，而 tao 初始化它时塞进去的是
     * `activity.getApplicationContext()`（见 tao 的 ndk_glue.rs）——**不是** Activity。
     * 所以 `(context as? Activity)` 永远为 null，`startActivity` 靠 NEW_TASK 标志还能跑，
     * `moveTaskToBack` 这种只能由 Activity 调的方法就是静默失效（v1.4.13 主页按返回
     * 没反应，就是这一条）。改由 MainActivity 在 onCreate 时登记自己。
     */
    private var currentActivity: WeakReference<Activity>? = null

    /** 由 MainActivity.onCreate 调用。 */
    @JvmStatic
    fun attachActivity(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    /** 由 MainActivity.onDestroy 调用；只有当时登记的还是自己时才清。 */
    @JvmStatic
    fun detachActivity(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runSafely("onMain", block)
        } else {
            main.post { runSafely("onMainAsync", block) }
        }
    }

    /** 把任何 Throwable 拦在边界内：绝不让它冒到 JNI 层。 */
    private inline fun <T> runSafely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 用系统浏览器打开链接（桌面端对应 `open_url`）。 */
    @JvmStatic
    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        onMain {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no activity for $url")
                toast(context, "没有可打开该链接的应用")
            } catch (e: Exception) {
                Log.w(TAG, "openUrl failed: ${e.message}")
            }
        }
    }

    /** 是否已在电池优化白名单里（true 表示系统不会因为这个应用的省电策略杀掉它）。 */
    @JvmStatic
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runSafely("isIgnoringBatteryOptimizations") {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } ?: false

    /**
     * 拉起系统的「不限制电量」确认框。
     *
     * 返回值：true = 本来就在白名单（无需操作）；false = 已（尝试）跳转到系统页面，
     * 等用户确认。跳转本身是异步的，所以这里只能表示「已发起」。
     *
     * 直接跳带包名的总弹窗在国产 ROM 上经常找不到，所以先试带包名的直连弹窗。
     */
    @JvmStatic
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true
        onMain {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            // 部分 ROM 没有这个入口，退回列表页
            if (!startQuietly(context, direct) &&
                !startQuietly(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            ) {
                toast(context, "无法打开电池优化设置，请在系统设置里手动允许后台运行")
            }
        }
        return false
    }

    private fun startQuietly(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "startActivity ${intent.action} failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * 打开「应用信息」页（其中可以改电池策略为「不受限制」）。
     *
     * 保活开关需要能关掉，但系统只提供「申请加入白名单」的 Intent，没有反向操作，
     * 因此只能把用户送到这里手动改回。
     *
     * 判断「有没有打开成功」必须在主线程闭包里完成：从 Rust 调进来时一定不在主线程，
     * `onMain` 是 post 投递，闭包外读到的标志位永远是初始值，会把 fallback 也执行一遍
     * （应用信息页和电池列表页接连弹两次）。所以整个分支都放进闭包，对外返回「已发起」。
     */
    @JvmStatic
    fun openAppSettings(context: Context) {
        onMain {
            val details = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
            // 个别 ROM 把详情页换了入口，退到电池优化的应用列表
            if (!startQuietly(context, details) &&
                !startQuietly(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            ) {
                toast(context, "无法打开系统设置，请手动进入「设置 → 应用 → 电池」")
            }
        }
    }

    /**
     * 把整个任务移到后台，效果等同用户按 Home 键。
     *
     * 主页按返回键时用它：Activity 不 finish、进程不退出，frpc 子进程和前台服务
     * 照旧跑着，从最近任务点回来还是原来的界面。
     * 注意不是 `finish()` —— finish 会销毁 Activity，界面状态（当前 tab、滚动位置、
     * 未保存的编辑）全丢，虽然隧道还在，但回来要从头点。
     *
     * `context` 参数是 Application（见 [currentActivity] 的说明），不能直接用它退后台，
     * 必须用登记进来的 Activity。
     */
    @JvmStatic
    fun moveToBack(context: Context) {
        onMain {
            val activity = currentActivity?.get()
            if (activity == null || activity.isFinishing) {
                // 理论上不该出现（返回键只在界面可见时按得到），真出现了也别当没听见：
                // 拉起桌面应用，至少让用户看到「退出来了」，隧道照样在跑。
                Log.w(TAG, "moveToBack: 没有可用的 Activity，改用 HOME Intent")
                goHome(context)
                return@onMain
            }
            // 分屏 / 悬浮窗 / 部分 ROM 上 moveTaskToBack 会返回 false，用 HOME Intent 兜一次
            if (!activity.moveTaskToBack(true)) {
                Log.w(TAG, "moveToBack: moveTaskToBack 返回 false，改用 HOME Intent")
                goHome(context)
            }
        }
    }

    /** 兜底：直接启动桌面（HOME）应用。 */
    private fun goHome(context: Context) {
        startQuietly(
            context,
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
        )
    }

    private fun toast(context: Context, text: String) {
        runSafely("toast") {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    /** Build 版本，供 Rust 侧日志用（JNI 返回值比读系统属性可靠）。 */
    @JvmStatic
    fun sdkInt(): Int = Build.VERSION.SDK_INT
}
