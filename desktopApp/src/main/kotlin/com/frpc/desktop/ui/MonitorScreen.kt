package com.frpc.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.frpc.desktop.AppContainer
import com.frpc.desktop.engine.FrpcSupervisor
import com.frpc.desktop.engine.LogProxyPhase.ERROR
import com.frpc.desktop.engine.LogProxyPhase.PENDING
import com.frpc.desktop.engine.LogProxyPhase.RUNNING
import com.frpc.shared.format.formatDuration
import com.frpc.shared.format.formatMemoryKb
import com.frpc.shared.format.formatPercent
import com.frpc.shared.model.FrpcNaming
import kotlinx.coroutines.delay

@Composable
fun MonitorScreen(container: AppContainer) {
    val state by container.supervisor.state.collectAsState()
    val metrics by container.supervisor.metrics.collectAsState()
    val status by container.supervisor.status.collectAsState()
    val logStates by container.supervisor.proxyStates.collectAsState()
    val visitorsOnline by container.supervisor.visitors.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val visitors = profiles.firstOrNull { it.id == state.profileId }?.config?.visitors.orEmpty()

    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(metrics) {
        val m = metrics ?: return@LaunchedEffect
        m.cpuPercent?.let { cpuHistory.add(it); if (cpuHistory.size > 120) cpuHistory.removeAt(0) }
        m.rssKb?.let { memHistory.add(it / 1024f); if (memHistory.size > 120) memHistory.removeAt(0) }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("监控", style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = "进程") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile("PID", state.pid?.toString() ?: "--", Modifier.weight(1f))
                StatTile("线程数", metrics?.threads?.toString() ?: "--", Modifier.weight(1f))
                StatTile(
                    "运行时长",
                    state.startedAtMs?.let { formatDuration(now - it) } ?: "--",
                    Modifier.weight(1f),
                )
                StatTile("自动重启", state.restartCount.toString(), Modifier.weight(1f))
            }
            HorizontalDivider()
            Text(
                text = when (state.phase) {
                    FrpcSupervisor.Phase.RUNNING -> "frpc 正在运行"
                    FrpcSupervisor.Phase.IDLE -> "frpc 未运行"
                    FrpcSupervisor.Phase.STARTING -> "正在启动"
                    FrpcSupervisor.Phase.STOPPING -> "正在停止"
                    FrpcSupervisor.Phase.ERROR -> state.error ?: "运行异常"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "CPU（%）") {
            Text(
                text = "当前 ${formatPercent(metrics?.cpuPercent)} · 峰值 ${cpuHistory.maxOrNull()?.let { "%.1f%%".format(it) } ?: "--"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cpuHistory.size < 2) {
                Text(
                    "采样中…（每 2 秒采一次，凑够 2 个点才画曲线）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Sparkline(values = cpuHistory.toList(), maxValue = (cpuHistory.maxOrNull() ?: 1f).coerceAtLeast(1f))
        }

        SectionCard(title = "内存（MB）") {
            Text(
                text = "当前 ${formatMemoryKb(metrics?.rssKb)} · 峰值 ${memHistory.maxOrNull()?.let { "%.1f MB".format(it) } ?: "--"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memHistory.size < 2) {
                Text(
                    "采样中…（每 2 秒采一次）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Sparkline(values = memHistory.toList(), maxValue = (memHistory.maxOrNull() ?: 1f).coerceAtLeast(1f))
        }

        SectionCard(title = "代理状态") {
            val items = status?.all.orEmpty()
            if (items.isEmpty() && logStates.isEmpty()) {
                Text(
                    "暂无数据：启动 frpc 并开启状态监控后这里会显示实时状态",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${item.type.uppercase()} · ${item.remoteAddr.ifBlank { item.localAddr }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.err.isNotBlank()) {
                            Text(
                                item.err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    StatusChip(
                        text = if (item.status == "running") "在线" else item.status,
                        color = if (item.status == "running") StatusRunning else StatusIdle,
                    )
                }
            }
            if (items.isEmpty()) {
                logStates.forEach { (name, logState) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        StatusChip(
                            text = when (logState.phase) {
                                RUNNING -> "在线（日志）"
                                PENDING -> "启动中"
                                ERROR -> "错误"
                            },
                            color = when (logState.phase) {
                                RUNNING -> StatusRunning
                                PENDING -> StatusStarting
                                ERROR -> StatusError
                            },
                        )
                    }
                }
            }
        }

        // 访问者：admin API 不返回，状态取自 "visitor added" 日志
        if (visitors.isNotEmpty()) {
            SectionCard(title = "访问者状态") {
                visitors.forEach { visitor ->
                    val online = visitor.name in visitorsOnline
                    val listen = if (visitor.bindPort < 0) "不监听端口" else "${visitor.bindAddr}:${visitor.bindPort}"
                    val target = FrpcNaming.targetServerProxyName(
                        profiles.firstOrNull { it.id == state.profileId }?.config?.user,
                        visitor.serverUser,
                        visitor.serverName,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(visitor.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${visitor.type.uppercase()} · 本地 $listen · 访问 $target",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 与主页一致：enabled = false 的访问者根本没注册，不该显示「启动中」
                        StatusChip(
                            text = when {
                                visitor.enabled == false -> "已禁用"
                                state.phase != FrpcSupervisor.Phase.RUNNING -> "未运行"
                                online -> "在线"
                                else -> "启动中"
                            },
                            color = when {
                                visitor.enabled == false -> StatusIdle
                                state.phase != FrpcSupervisor.Phase.RUNNING -> StatusIdle
                                online -> StatusRunning
                                else -> StatusStarting
                            },
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.height(8.dp)) {}
    }
}

@Composable
private fun Sparkline(values: List<Float>, maxValue: Float) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        // 三条水平参考线
        repeat(3) { i ->
            val y = h * i / 2f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (values.size < 2) return@Canvas
        val stepX = w / (values.size - 1).toFloat()
        var prev: Offset? = null
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val y = h - (value / maxValue) * (h - 4f) - 2f
            val point = Offset(x, y)
            prev?.let { drawLine(lineColor, it, point, strokeWidth = 2.2f) }
            prev = point
        }
    }
}
