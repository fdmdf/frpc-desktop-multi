package com.frpc.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.frpc.android.MainActivity
import com.frpc.android.R

/** 常驻通知展示数据（由服务侧组装） */
data class StatusNotificationData(
    val state: FrpcSupervisor.RuntimeState,
    val onlineCount: Int,
    val totalCount: Int,
    val visitorOnline: Int = 0,
    val visitorTotal: Int = 0,
    val serverSummary: String,
    val uptimeText: String,
    val cpuText: String,
    val memText: String,
)

/** 通知：常驻状态（低优先级）+ 事件提醒（默认优先级） */
object Notifications {

    const val CHANNEL_STATUS = "frpc_status"
    const val CHANNEL_EVENTS = "frpc_events"
    const val ONGOING_ID = 1001
    private const val EVENT_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "frpc 运行状态常驻通知"
            setShowBadge(false)
        }
        val eventChannel = NotificationChannel(
            CHANNEL_EVENTS,
            context.getString(R.string.notification_channel_events),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "frpc 异常与事件提醒"
        }
        manager.createNotificationChannels(listOf(statusChannel, eventChannel))
    }

    fun buildStatusNotification(context: Context, data: StatusNotificationData): Notification {
        val state = data.state
        val contentText: String
        val bigText: String

        when (state.phase) {
            FrpcSupervisor.Phase.RUNNING -> {
                contentText = context.getString(
                    R.string.notification_collapsed,
                    state.profileName,
                    data.onlineCount,
                    data.totalCount,
                    data.uptimeText,
                )
                bigText = buildString {
                    if (data.serverSummary.isNotBlank()) {
                        append(context.getString(R.string.notification_line_server, data.serverSummary))
                        append('\n')
                    }
                    append(
                        context.getString(
                            R.string.notification_line_proxies,
                            data.onlineCount,
                            data.totalCount,
                            data.uptimeText,
                        ),
                    )
                    append('\n')
                    // 有访问者时单独一行，避免折叠态信息过长
                    if (data.visitorTotal > 0) {
                        append(
                            context.getString(
                                R.string.notification_line_visitors,
                                data.visitorOnline,
                                data.visitorTotal,
                            ),
                        )
                        append('\n')
                    }
                    append(
                        context.getString(
                            R.string.notification_line_resources,
                            data.cpuText,
                            data.memText,
                        ),
                    )
                }
            }
            FrpcSupervisor.Phase.STARTING -> {
                contentText = context.getString(R.string.notification_starting)
                bigText = contentText
            }
            FrpcSupervisor.Phase.ERROR -> {
                contentText = context.getString(R.string.notification_error, state.error ?: "")
                bigText = contentText
            }
            else -> {
                contentText = context.getString(R.string.notification_stopped)
                bigText = contentText
            }
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            FrpcService.stopIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_frpc)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.action_stop), stopIntent)
            .build()
    }

    fun updateStatusNotification(context: Context, data: StatusNotificationData) {
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ONGOING_ID, buildStatusNotification(context, data))
        }
    }

    fun postEvent(context: Context, text: String) {
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_frpc)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(EVENT_ID, notification)
        }
    }
}
