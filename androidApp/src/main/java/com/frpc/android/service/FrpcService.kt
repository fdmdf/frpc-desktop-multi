package com.frpc.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.frpc.android.FrpcApplication
import com.frpc.android.R
import com.frpc.shared.format.formatDuration
import com.frpc.shared.format.formatMemoryKb
import com.frpc.shared.format.formatPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * frpc 常驻前台服务。
 * - START：拉起 FrpcSupervisor 运行指定配置，服务提升为前台（specialUse）
 * - STOP / RELOAD：转发给 Supervisor
 * - 运行时状态变化时刷新常驻通知；回到 IDLE 时自停
 */
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
                // ACTION_START 或系统重启（START_STICKY 重投时 intent 为 null → 重启默认配置）
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
        // 通知用户可感知的启动失败原因
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

    /** 组装常驻通知数据：服务器 / 在线代理 / 运行时长 / CPU / 内存 */
    private fun buildNotificationData(): StatusNotificationData {
        val st = FrpcSupervisor.state.value
        val container = (applicationContext as FrpcApplication).container
        val profile = container.configRepository.find(st.profileId)
        // 分母与「在线数」同口径：运行中就用 frpc 真正加载的那份配置
        // （禁用一条但没重载时，最新配置会少一条，frpc 却还在跑它 → 分子大于分母）
        val counts = FrpcSupervisor.activeCounts.value
        val runningNow = st.phase == FrpcSupervisor.Phase.RUNNING
        val total = if (runningNow) {
            counts.proxies
        } else {
            profile?.config?.proxies?.count { it.enabled != false } ?: 0
        }
        // Admin 优先，其次日志推断（与主页保持一致）
        val online = FrpcSupervisor.status.value?.all?.count { it.status == "running" }
            ?: FrpcSupervisor.logProxyStates.value.count { it.value.phase == LogProxyPhase.RUNNING }
        val uptime = st.startedAtMs
            ?.let { formatDuration(System.currentTimeMillis() - it) }
            ?: "--"
        // 访问者同样只注册一次，状态取自日志
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
                    // 仅在服务真正运行过后回到 IDLE（用户停止）才退场，避免启动竞态误停
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
        // 运行中每 5 秒刷新一次（运行时长/CPU/内存/在线数实时变化）
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
