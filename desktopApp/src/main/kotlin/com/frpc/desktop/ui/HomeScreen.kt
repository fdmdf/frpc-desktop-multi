package com.frpc.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frpc.desktop.AppContainer
import com.frpc.desktop.engine.FrpcSupervisor
import com.frpc.desktop.engine.LogProxyPhase
import com.frpc.desktop.data.ServerProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun HomeScreen(
    container: AppContainer,
    onEditProfile: (String) -> Unit,
    onOpenStatusPage: (Int) -> Unit,
) {
    val profiles by container.profiles.profiles.collectAsState()
    val settings by container.settings.state.collectAsState()
    val state by container.supervisor.state.collectAsState()
    val pendingReloadId by container.supervisor.pendingReloadProfileId.collectAsState()
    val metrics by container.supervisor.metrics.collectAsState()
    val status by container.supervisor.status.collectAsState()
    val logStates by container.supervisor.proxyStates.collectAsState()
    val visitorsOnline by container.supervisor.visitors.collectAsState()
    val activeCounts by container.supervisor.activeCounts.collectAsState()
    val adminReachable by container.supervisor.adminReachable.collectAsState()
    val serverConnected by container.supervisor.serverConnected.collectAsState()
    val loginError by container.supervisor.loginError.collectAsState()

    val currentProfile = profiles.firstOrNull { it.id == (state.profileId ?: settings.defaultProfileId) }
        ?: profiles.firstOrNull()

    var blockedReason by remember { mutableStateOf<String?>(null) }
    var switchTarget by remember { mutableStateOf<ServerProfile?>(null) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val running = state.phase == FrpcSupervisor.Phase.RUNNING
    val proxies = currentProfile?.config?.proxies.orEmpty()
    val visitors = currentProfile?.config?.visitors.orEmpty()
    val enabledProxies = proxies.filter { it.enabled != false }
    val enabledVisitors = visitors.filter { it.enabled != false }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }

    fun setEnabled(profile: ServerProfile, name: String, enabled: Boolean, visitor: Boolean) {
        val updated = profile.copy(
            config = if (visitor) {
                profile.config.copy(
                    visitors = profile.config.visitors.map {
                        if (it.name == name) it.copy(enabled = if (enabled) null else false) else it
                    },
                )
            } else {
                profile.config.copy(
                    proxies = profile.config.proxies.map {
                        if (it.name == name) it.copy(enabled = if (enabled) null else false) else it
                    },
                )
            },
        )
        scope.launch {
            container.profiles.upsert(updated)
            if (running) container.supervisor.reload()
        }
    }

    fun setProxyEnabled(profile: ServerProfile, proxyName: String, enabled: Boolean) =
        setEnabled(profile, proxyName, enabled, visitor = false)

    fun setVisitorEnabled(profile: ServerProfile, visitorName: String, enabled: Boolean) =
        setEnabled(profile, visitorName, enabled, visitor = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("运行状态", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "frpc 引擎 ${container.engine.bundledVersion()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (profiles.size > 1) {
                DropdownField(
                    label = "当前配置",
                    selected = currentProfile ?: profiles.first(),
                    options = profiles,
                    optionLabel = { it.name },
                    onSelect = { target ->
                        if (running && target.id != currentProfile?.id) {
                            switchTarget = target
                        } else {
                            container.settings.setDefaultProfileId(target.id)
                        }
                    },
                    modifier = Modifier.width(280.dp),
                )
            }
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (label, color) = when {
                    state.phase == FrpcSupervisor.Phase.RUNNING && serverConnected == false ->
                        "未连上服务器" to StatusError
                    state.phase == FrpcSupervisor.Phase.RUNNING -> "运行中" to StatusRunning
                    state.phase == FrpcSupervisor.Phase.STARTING -> "启动中" to StatusStarting
                    state.phase == FrpcSupervisor.Phase.STOPPING -> "停止中" to StatusStarting
                    state.phase == FrpcSupervisor.Phase.ERROR -> "异常" to StatusError
                    else -> "未运行" to StatusIdle
                }
                StatusChip(label, color)
                Text(
                    text = state.profileName.ifBlank { currentProfile?.name.orEmpty() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = currentProfile?.serverSummary.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val detail = buildString {
                if (state.pid != null) append("PID ${state.pid}")
                if (state.startedAtMs != null) {
                    if (isNotEmpty()) append(" · ")
                    append("启动于 ${formatDateTime(state.startedAtMs!!)}")
                }
                if (state.restartCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("已自动重启 ${state.restartCount} 次")
                }
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.error != null) {
                Text(
                    text = state.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (running && serverConnected == false) {
                Text(
                    text = "连接 frps 失败：" + (loginError ?: "未知原因") +
                        "。frpc 会持续重试；常见原因是 token 不一致或服务器地址/端口不对，详情见日志页。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (running && state.adminEnabled && state.adminPort > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onOpenStatusPage(state.adminPort) }) {
                        Text("打开 frp 面板（浏览器需登录）")
                    }
                    Text(
                        text = "http://127.0.0.1:${state.adminPort}/static/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!adminReachable) {
                        Text(
                            text = " · 状态接口暂不可达（同机多个 frpc 时，检查端口 ${state.adminPort} 是否被占用）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (currentProfile == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "还没有配置：先在「配置」里新建一份，或导入现成的 frpc.toml",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (running) {
                            container.supervisor.stop()
                        } else {
                            val profile = currentProfile ?: return@Button
                            val reason = container.supervisor.checkBeforeStart(profile)
                            if (reason == null) {
                                container.supervisor.start(profile)
                            } else {
                                blockedReason = reason
                            }
                        }
                    },
                    enabled = currentProfile != null &&
                        state.phase != FrpcSupervisor.Phase.STARTING &&
                        state.phase != FrpcSupervisor.Phase.STOPPING,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.phase == FrpcSupervisor.Phase.STARTING ||
                        state.phase == FrpcSupervisor.Phase.STOPPING
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text(if (running) "停止" else "启动")
                    }
                }
                OutlinedButton(
                    onClick = { container.supervisor.reload() },
                    enabled = running,
                ) { Text("重载配置") }
                OutlinedButton(
                    onClick = { currentProfile?.let { onEditProfile(it.id) } },
                    enabled = currentProfile != null,
                ) { Text("编辑配置") }
            }
        }

        if (running && pendingReloadId != null && pendingReloadId == state.profileId) {
            SectionCard(title = "配置尚未生效") {
                Text(
                    text = "配置已保存但还没有重载，frpc 仍在使用旧配置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = { container.supervisor.reload() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重载配置") }
            }
        }

        SectionCard(title = "概览") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StatTile("CPU", formatPercent(metrics?.cpuPercent), Modifier.weight(1f))
                StatTile("内存", formatMemoryKb(metrics?.rssKb), Modifier.weight(1f))
                StatTile(
                    "运行时长",
                    state.startedAtMs?.let { formatDuration(now - it) } ?: "--",
                    Modifier.weight(1f),
                )
                StatTile(
                    "在线代理",
                    proxySummary(
                        proxies,
                        if (running) activeCounts.proxies else enabledProxies.size,
                        status?.all?.size,
                        status?.all?.count { it.status == "running" },
                    ),
                    Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Text(
                text = when {
                    !running -> "启动后显示 CPU / 内存 / 运行时长与在线代理数"
                    state.adminEnabled -> "代理状态与真实端口来自状态接口（webServer），每 4 秒刷新；访问者状态来自日志"
                    else -> "未开启状态接口：代理与访问者状态都按日志推断，可能拿不到真实远端端口"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (proxies.isEmpty()) {
            SectionCard(title = "代理规则") {
                Text(
                    text = "这个配置还没有代理规则，去「配置」里添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "代理规则（${enabledProxies.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                copied?.let {
                    Text(
                        text = "已复制：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        proxies.forEach { proxy ->
            val adminItem = status?.item(proxy.name)
            val logState = logStates[proxy.name]
            val (label, color) = when {
                proxy.enabled == false -> "已禁用" to StatusIdle
                adminItem?.status == "running" -> "在线" to StatusRunning
                adminItem?.status == "error" -> "错误" to StatusError
                adminItem != null -> adminItem.status to StatusIdle
                logState?.phase == LogProxyPhase.RUNNING -> "在线" to StatusRunning
                logState?.phase == LogProxyPhase.ERROR -> "错误" to StatusError
                logState?.phase == LogProxyPhase.PENDING -> "启动中" to StatusStarting
                running -> "未知" to StatusIdle
                else -> "未运行" to StatusIdle
            }
            val proxyType = proxy.proxyType
            val remote = when {
                !adminItem?.remoteAddr.isNullOrBlank() -> adminItem?.remoteAddr ?: ""
                proxyType?.needsRemotePort != true -> when {
                    proxyType?.domainBased == true ->
                        proxy.customDomains?.firstOrNull() ?: proxy.subdomain ?: "域名待配置"
                    else -> "私密代理（由访问者接入）"
                }
                proxy.remotePort == null || proxy.remotePort == 0 -> "远程端口随机"
                else -> "远程 :${proxy.remotePort}"
            }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(proxy.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${proxy.type.uppercase()} · 本地 ${proxy.localIP ?: "127.0.0.1"}:${proxy.localPort ?: "?"} → $remote",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val error = adminItem?.err?.takeIf { it.isNotBlank() } ?: logState?.error
                        if (error != null) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = {
                        copyToClipboard(remote)
                        copied = remote
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制访问地址")
                    }
                    StatusChip(label, color)
                    Switch(
                        checked = proxy.enabled != false,
                        onCheckedChange = { on ->
                            currentProfile?.let { setProxyEnabled(it, proxy.name, on) }
                        },
                    )
                }
            }
        }

        if (visitors.isNotEmpty()) {
            Text(
                text = "访问者（${enabledVisitors.size}）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        visitors.forEach { visitor ->
            val online = running && visitor.name in visitorsOnline
            val (label, color) = when {
                visitor.enabled == false -> "已禁用" to StatusIdle
                online -> "在线" to StatusRunning
                running -> "启动中" to StatusStarting
                else -> "未运行" to StatusIdle
            }
            val listen = if (visitor.bindPort < 0) "不监听端口" else "${visitor.bindAddr}:${visitor.bindPort}"
            val target = com.frpc.shared.model.FrpcNaming.targetServerProxyName(
                currentProfile?.config?.user, visitor.serverUser, visitor.serverName,
            )

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(visitor.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${visitor.type.uppercase()} · 本地 $listen · 访问 $target",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(label, color)
                    Switch(
                        checked = visitor.enabled != false,
                        onCheckedChange = { on ->
                            currentProfile?.let { setVisitorEnabled(it, visitor.name, on) }
                        },
                    )
                }
            }
        }

        Box(modifier = Modifier.padding(bottom = 8.dp))
    }

    switchTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text("切换配置") },
            text = {
                Text(
                    "frpc 正在运行「${currentProfile?.name.orEmpty()}」。切换到「${target.name}」" +
                        "会停止当前进程并用新配置重新启动，现有连接会中断。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        switchTarget = null
                        scope.launch {
                            container.settings.setDefaultProfileId(target.id)
                            container.supervisor.stop()
                            withTimeoutOrNull(10_000) {
                                container.supervisor.state.first {
                                    it.phase == FrpcSupervisor.Phase.IDLE ||
                                        it.phase == FrpcSupervisor.Phase.ERROR
                                }
                            }
                            val reason = container.supervisor.checkBeforeStart(target)
                            if (reason == null) {
                                container.supervisor.start(target)
                            } else {
                                blockedReason = reason
                            }
                        }
                    },
                ) { Text("切换并重启") }
            },
            dismissButton = {
                TextButton(onClick = { switchTarget = null }) { Text("取消") }
            },
        )
    }

    blockedReason?.let { reason ->
        AlertDialog(
            onDismissRequest = { blockedReason = null },
            title = { Text("无法启动") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(
                    onClick = {
                        blockedReason = null
                        currentProfile?.let { onEditProfile(it.id) }
                    },
                ) { Text("去配置") }
            },
            dismissButton = {
                TextButton(onClick = { blockedReason = null }) { Text("知道了") }
            },
        )
    }
}

private fun copyToClipboard(text: String) {
    runCatching {
        val selection = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }
}

private fun proxySummary(
    proxies: List<com.frpc.shared.model.ProxyConfig>,
    effectiveTotal: Int,
    totalFromAdmin: Int?,
    onlineFromAdmin: Int?,
): String {
    val total = totalFromAdmin ?: effectiveTotal
    val online = onlineFromAdmin ?: 0
    return if (total > 0) "$online/$total" else "--"
}
