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
 * 通知内容由前端（Rust → JNI）在隧道状态变化时更新，所以这里的文案是多语言的。
 */
class FrpcKeepAliveService : Service() {

    companion object {
        private const val TAG = "FrpcKeepAlive"
        private const val CHANNEL_ID = "frpc_tunnel"
        private const val NOTIFICATION_ID = 0x4652 // "FR"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** 供 Rust 侧查询，避免每次状态变化都重复 startService。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, FrpcKeepAliveService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
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
        try {
            startForeground(NOTIFICATION_ID, buildNotification(title, text))
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
    private fun buildNotification(title: String, text: String): Notification {
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
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            // 必须是纯白 + alpha 的单色图，否则状态栏会渲染成一个白方块
            .setSmallIcon(R.drawable.ic_stat_frpc)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }
}
