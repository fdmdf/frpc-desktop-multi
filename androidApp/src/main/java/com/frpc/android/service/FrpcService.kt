package com.frpc.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.frpc.android.FrpcApplication
import com.frpc.android.MainActivity
import com.frpc.android.R
import com.frpc.android.util.formatDuration
import com.frpc.android.util.formatMemoryKb
import com.frpc.android.util.formatPercent
import com.frpc.shared.admin.ProxyStatusItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FrpcService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FrpcSupervisor.requestStop()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                FrpcSupervisor.requestReload()
                return START_STICKY
            }
            else -> {
                val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
                startAsForeground()
                scope.launch {
                    val container = (applicationContext as FrpcApplication).container
                    val profile = profileId
                        ?.let { container.configRepository.find(it) }
                        ?: container.settingsRepository.defaultProfileId.value
                            ?.let { container.configRepository.find(it) }
                    if (profile == null) {
                        appendSystemLog("没有可用配置，服务停止")
                        stopSelfSafely()
                        return@launch
                    }
                    FrpcSupervisor.requestStart(profile)
                }
                observeRuntime()
                return START_STICKY
            }
        }
    }

    private fun appendSystemLog(message: String) {
        Notifications.postEvent(this, message)
    }

    private fun startAsForeground() {
        val notification = Notifications.buildStatusNotification(this, buildNotificationData())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Notifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ONGOING_ID, notification)
        }
    }

    private fun buildNotificationData(): StatusNotificationData {
        val st = FrpcSupervisor.state.value
        val container = (applicationContext as FrpcApplication).container
        val profile = container.configRepository.find(st.profileId)
        val counts = FrpcSupervisor.activeCounts.value
        val runningNow = st.phase == FrpcSupervisor.Phase.RUNNING
        val total = if (runningNow) {
            counts.proxies
        } else {
            profile?.config?.proxies?.count { it.enabled != false } ?: 0
        }
        val online = FrpcSupervisor.status.value?.all?.count { it.status == "running" }
            ?: FrpcSupervisor.logProxyStates.value.count { it.value.phase == LogProxyPhase.RUNNING }
        val uptime = st.startedAtMs
            ?.let { formatDuration(System.currentTimeMillis() - it) }
            ?: "--"
        val visitorTotal = if (runningNow) {
            counts.visitors
        } else {
            profile?.config?.visitors?.count { it.enabled != false } ?: 0
        }
        val visitorOnline = profile?.config?.visitors
            ?.count { it.enabled != false && it.name in FrpcSupervisor.visitors.value } ?: 0
        return StatusNotificationData(
            state = st,
            onlineCount = online,
            totalCount = total,
            visitorOnline = visitorOnline,
            visitorTotal = visitorTotal,
            serverSummary = profile?.serverSummary ?: "",
            uptimeText = uptime,
            cpuText = formatPercent(FrpcSupervisor.cpuPercent.value),
            memText = FrpcSupervisor.stats.value?.let { formatMemoryKb(it.rssKb) } ?: "--",
        )
    }

    private fun refreshNotification() {
        Notifications.updateStatusNotification(this, buildNotificationData())
    }

    private var observing = false
    private var everActive = false

    private fun observeRuntime() {
        if (observing) return
        observing = true
        scope.launch {
            FrpcSupervisor.state.collectLatest { st ->
                if (st.phase != FrpcSupervisor.Phase.IDLE) everActive = true
                refreshNotification()
                when (st.phase) {
                    FrpcSupervisor.Phase.IDLE -> if (everActive) stopSelfSafely()
                    FrpcSupervisor.Phase.ERROR -> {
                        Notifications.postEvent(
                            this@FrpcService,
                            st.error ?: getString(R.string.phase_error),
                        )
                        stopSelfSafely()
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5000)
                if (FrpcSupervisor.state.value.phase == FrpcSupervisor.Phase.RUNNING) {
                    refreshNotification()
                }
            }
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.frpc.android.action.START"
        const val ACTION_STOP = "com.frpc.android.action.STOP"
        const val ACTION_RELOAD = "com.frpc.android.action.RELOAD"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        fun startIntent(context: Context, profileId: String): Intent =
            Intent(context, FrpcService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, FrpcService::class.java).apply { action = ACTION_STOP }

        fun reloadIntent(context: Context): Intent =
            Intent(context, FrpcService::class.java).apply { action = ACTION_RELOAD }
    }
}
