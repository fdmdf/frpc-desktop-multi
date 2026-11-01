package io.github.fdmdf.frpc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.fdmdf.frpc.MainActivity
import io.github.fdmdf.frpc.R

/**
 * 隧道运行期的前台服务：挂一条常驻通知，把进程优先级提到「前台」。
 *
 * 为什么需要它：Tauri 版的 frpc 是 Rust 监管器 `Command::spawn` 出来的**子进程**，
 * 父进程（应用进程）被系统回收时子进程跟着死，隧道就断了。Android 的进程回收是按
 * 「有没有前台组件」排序的：只有 Activity 时，切后台几分钟就可能被 LMK 干掉；
 * 有一个前台服务并挂着通知，就会被当成用户可见的工作，基本不会被回收，
 * 在国产 ROM 的省电策略里也从「可清理」变成「正在运行」。
 *
 * 注意这只解决「被系统杀」，不解决「用户手动强行停止 / 划掉后台被某些 ROM 立刻清」——
 * 后者只能靠 `FrpcHost.requestIgnoreBatteryOptimizations()` 引导用户加白名单。
 *
 * 通知内容由前端（Rust → JNI）在隧道状态变化时下发，所以文案是多语言的：
 * 折叠态一行摘要，展开态多行明细（BigTextStyle），右侧计时器由**系统**自己走 ——
 * 不这样的话，要么计时不准，要么得常驻一个 JS 定时器往这里推。
 */
class FrpcKeepAliveService : Service() {

    companion object {
        private const val TAG = "FrpcKeepAlive"
        private const val CHANNEL_ID = "frpc_tunnel"
        private const val NOTIFICATION_ID = 0x4652 // "FR"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_LINES = "lines"
        private const val EXTRA_SINCE = "since"

        /** 多行明细由 Rust 侧用换行拼好传进来，这里直接当 bigText 用 */

        /** 品牌色：Android 12+ 会把通知小图标染成这个颜色。
         *  用 val 而不是 const —— `0xFF0B57D0.toInt()` 不是编译期常量表达式。 */
        private val BRAND_COLOR = 0xFF0B57D0.toInt()

        /** 供 Rust 侧查询，避免每次状态变化都重复 startService。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 拉起（或就地更新）常驻通知。
         *
         * @param title 通知标题，如「隧道运行中」
         * @param text 折叠态的一行摘要，如配置名
         * @param lines 展开态明细，多行用 `\n` 分隔
         * @param since 隧道启动时刻（epoch ms），> 0 时通知右侧显示系统计时器
         */
        // @JvmStatic 不能省：companion object 的方法默认是 Companion 实例方法，
        // JVM 里根本不存在 `FrpcKeepAliveService.start(...)` 这个**静态**方法，
        // Rust 侧 call_static_method 会直接抛 NoSuchMethodError。
        @JvmStatic
        fun start(context: Context, title: String, text: String, lines: String, since: Long) {
            val intent = Intent(context, FrpcKeepAliveService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_LINES, lines)
                putExtra(EXTRA_SINCE, since)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
                // 这里不能让它把应用带崩：隧道本身还在跑，只是少了保活。
                Log.w(TAG, "startForegroundService failed: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FrpcKeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "frpc-desktop-multi"
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val lines = intent?.getStringExtra(EXTRA_LINES).orEmpty()
        val since = intent?.getLongExtra(EXTRA_SINCE, 0L) ?: 0L
        try {
            startForeground(NOTIFICATION_ID, buildNotification(title, text, lines, since))
            isRunning = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
        // START_STICKY：系统回收进程后会尝试重建服务。Rust 侧的隧道状态此时已丢失，
        // 但至少通知不会残留成「假运行」，用户点进来能看到真实状态。
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "frpc 隧道",
            // LOW：常驻通知不该响、不该弹，只在通知栏占一行
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "隧道运行期间保持后台连接"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(
        title: String,
        text: String,
        lines: String,
        since: Long,
    ): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        builder
            .setContentTitle(title)
            .setContentText(text)
            // 必须是纯白 + alpha 的单色图，否则状态栏会渲染成一个白方块
            .setSmallIcon(R.drawable.ic_stat_frpc)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(BRAND_COLOR)
            .setCategory(Notification.CATEGORY_SERVICE)
            // 锁屏也显示：这是一条「正在工作」的状态通知，不是敏感内容
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // 展开态的多行明细；没有明细时保持单行，避免出现一个空的大文本框
        if (lines.isNotBlank()) {
            builder.setStyle(Notification.BigTextStyle().bigText(lines))
        }

        // 计时器交给系统：不占前端定时器，应用被冻结也不会停表
        if (since > 0L) {
            builder.setWhen(since).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }

        // API 31+：不加这个，从后台启动的前台服务通知会延迟约 10 秒才出现
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
