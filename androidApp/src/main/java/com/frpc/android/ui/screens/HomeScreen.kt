package com.frpc.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpc.android.R
import com.frpc.android.data.ServerProfile
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcService
import com.frpc.android.service.FrpcSupervisor
import com.frpc.android.service.LogProxyPhase
import com.frpc.android.ui.components.GroupHeader
import com.frpc.android.ui.components.SectionCard
import com.frpc.android.ui.components.StatusChip
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.android.ui.nav.Screen
import com.frpc.android.ui.theme.StatusError
import com.frpc.android.ui.theme.StatusIdle
import com.frpc.android.ui.theme.StatusRunning
import com.frpc.android.ui.theme.StatusStarting
import com.frpc.android.util.formatClock
import com.frpc.android.util.formatDuration
import com.frpc.android.util.formatMemoryKb
import com.frpc.android.util.formatPercent
import com.frpc.shared.model.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SWITCH_STOP_TIMEOUT_MS = 10_000L

@Composable
fun HomeScreen(container: AppContainer, navigator: AppNavigator) {
    val context = LocalContext.current
    val state by FrpcSupervisor.state.collectAsStateWithLifecycle()
    val status by FrpcSupervisor.status.collectAsStateWithLifecycle()
    val logProxyStates by FrpcSupervisor.logProxyStates.collectAsStateWithLifecycle()
    val visitorsOnline by FrpcSupervisor.visitors.collectAsStateWithLifecycle()
    val activeCounts by FrpcSupervisor.activeCounts.collectAsStateWithLifecycle()
    val stats by FrpcSupervisor.stats.collectAsStateWithLifecycle()
    val cpu by FrpcSupervisor.cpuPercent.collectAsStateWithLifecycle()
    val adminReachable by FrpcSupervisor.adminReachable.collectAsStateWithLifecycle()
    val serverConnected by FrpcSupervisor.serverConnected.collectAsStateWithLifecycle()
    val loginError by FrpcSupervisor.loginError.collectAsStateWithLifecycle()
    val pendingReloadId by FrpcSupervisor.pendingReloadProfileId.collectAsStateWithLifecycle()
    val profiles by container.configRepository.profiles.collectAsStateWithLifecycle()
    val defaultId by container.settingsRepository.defaultProfileId.collectAsStateWithLifecycle()

    val currentProfileId = state.profileId ?: defaultId ?: profiles.firstOrNull()?.id
    val currentProfile = profiles.firstOrNull { it.id == currentProfileId }
    val uiScope = rememberCoroutineScope()

    var blockedReason by remember { mutableStateOf<FrpcSupervisor.StartBlockReason?>(null) }
    var checking by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<ServerProfile?>(null) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase) {
        while (FrpcSupervisor.state.value.phase == FrpcSupervisor.Phase.RUNNING) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptime = state.startedAtMs?.let { formatDuration(nowMs - it) } ?: "--"
    val startedAtText = state.startedAtMs?.let { formatClock(it) }

    val loginFailed = state.phase == FrpcSupervisor.Phase.RUNNING && serverConnected == false
    val phaseLabel = when {
        loginFailed -> stringResource(R.string.phase_connect_failed)
        state.phase == FrpcSupervisor.Phase.IDLE -> stringResource(R.string.phase_idle)
        state.phase == FrpcSupervisor.Phase.STARTING -> stringResource(R.string.phase_starting)
        state.phase == FrpcSupervisor.Phase.RUNNING -> stringResource(R.string.phase_running)
        state.phase == FrpcSupervisor.Phase.STOPPING -> stringResource(R.string.phase_stopping)
        else -> stringResource(R.string.phase_error)
    }
    val phaseColor = when {
        loginFailed -> StatusError
        state.phase == FrpcSupervisor.Phase.RUNNING -> StatusRunning
        state.phase == FrpcSupervisor.Phase.STARTING -> StatusStarting
        state.phase == FrpcSupervisor.Phase.ERROR -> StatusError
        else -> StatusIdle
    }

    val proxies = proxiesOf(currentProfile)
    val visitors = currentProfile?.config?.visitors.orEmpty()
    val enabledProxies = proxies.filter { it.enabled != false }
    val enabledVisitors = visitors.filter { it.enabled != false }
    val onlineCount = status?.all?.count { it.status == "running" }
        ?: logProxyStates.count { it.value.phase == LogProxyPhase.RUNNING }
    val totalCount = if (state.phase == FrpcSupervisor.Phase.RUNNING) {
        activeCounts.proxies
    } else {
        enabledProxies.size
    }

    fun setRuleEnabled(name: String, enabled: Boolean, isVisitor: Boolean) {
        val profile = currentProfile ?: return
        val updated = profile.copy(
            config = if (isVisitor) {
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
        uiScope.launch {
            container.configRepository.upsert(updated)
            if (state.phase == FrpcSupervisor.Phase.RUNNING) {
                FrpcSupervisor.markPendingReload(updated.id)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = stringResource(R.string.home_status)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(text = phaseLabel, color = phaseColor)
                    if (state.phase == FrpcSupervisor.Phase.RUNNING) {
                        Text(
                            text = " · ${state.profileName}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (state.error != null) {
                    Text(
                        text = state.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (loginFailed) {
                    Text(
                        text = stringResource(
                            R.string.connect_failed_hint,
                            loginError ?: "",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.phase == FrpcSupervisor.Phase.RUNNING) {
                    Text(
                        text = buildString {
                            append(currentProfile?.serverSummary ?: "")
                            if (state.pid != null) append(" · PID ${state.pid}")
                            if (startedAtText != null) {
                                append(" · ").append(stringResource(R.string.home_started_at, startedAtText))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.adminEnabled && state.adminPort > 0) {
                        Text(
                            text = stringResource(
                                R.string.home_status_page,
                                "http://127.0.0.1:${state.adminPort}/static/",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val running = state.phase == FrpcSupervisor.Phase.RUNNING
                    val busy = checking ||
                        state.phase == FrpcSupervisor.Phase.STARTING ||
                        state.phase == FrpcSupervisor.Phase.STOPPING
                    Button(
                        onClick = {
                            if (running) {
                                context.startService(FrpcService.stopIntent(context))
                            } else {
                                val profile = currentProfile ?: return@Button
                                checking = true
                                uiScope.launch(Dispatchers.IO) {
                                    val reason = FrpcSupervisor.checkBeforeStart(profile)
                                    withContext(Dispatchers.Main) {
                                        checking = false
                                        if (reason == null) {
                                            ContextCompat.startForegroundService(
                                                context,
                                                FrpcService.startIntent(context, profile.id),
                                            )
                                        } else {
                                            blockedReason = reason
                                        }
                                    }
                                }
                            }
                        },
                        enabled = currentProfile != null && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text(
                                stringResource(
                                    if (running) R.string.action_stop else R.string.action_start,
                                ),
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { context.startService(FrpcService.reloadIntent(context)) },
                        enabled = state.phase == FrpcSupervisor.Phase.RUNNING,
                    ) {
                        Text(stringResource(R.string.action_reload))
                    }
                }
                if (currentProfile == null) {
                    TextButton(onClick = { navigator.selectTab(Screen.ConfigList) }) {
                        Text(stringResource(R.string.home_no_config))
                    }
                }
            }
        }

        if (state.phase == FrpcSupervisor.Phase.RUNNING && pendingReloadId == currentProfileId) {
            item {
                SectionCard(title = stringResource(R.string.home_pending_reload_title)) {
                    Text(
                        text = stringResource(R.string.home_pending_reload_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { context.startService(FrpcService.reloadIntent(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_reload))
                    }
                }
            }
        }

        if (profiles.size > 1) {
            item {
                SectionCard(title = stringResource(R.string.home_current_config)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        profiles.forEach { profile ->
                            FilterChip(
                                selected = profile.id == currentProfileId,
                                onClick = {
                                    if (profile.id == currentProfileId) return@FilterChip
                                    if (state.phase == FrpcSupervisor.Phase.RUNNING) {
                                        switchTarget = profile
                                    } else {
                                        uiScope.launch {
                                            container.settingsRepository.setDefaultProfileId(profile.id)
                                        }
                                    }
                                },
                                label = { Text(profile.name, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.home_quick_stats)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell(stringResource(R.string.home_cpu), formatPercent(cpu))
                    StatCell(
                        stringResource(R.string.home_memory),
                        stats?.let { formatMemoryKb(it.rssKb) } ?: "--",
                    )
                    StatCell(stringResource(R.string.home_uptime), uptime)
                    StatCell(
                        stringResource(R.string.home_online_proxies),
                        if (totalCount > 0) "$onlineCount/$totalCount" else "--",
                    )
                }
                if (state.phase == FrpcSupervisor.Phase.RUNNING && state.adminEnabled && !adminReachable) {
                    Text(
                        text = stringResource(R.string.home_admin_unreachable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!state.adminEnabled && state.phase == FrpcSupervisor.Phase.RUNNING) {
                    Text(
                        text = stringResource(R.string.home_admin_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            GroupHeader(
                text = if (proxies.isEmpty()) {
                    stringResource(R.string.home_proxies)
                } else {
                    stringResource(
                        R.string.home_group_count,
                        stringResource(R.string.home_proxies),
                        enabledProxies.size,
                    )
                },
            )
        }
        if (proxies.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.proxies_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(proxies) { proxy ->
            val adminItem = status?.item(proxy.name)
            val logState = logProxyStates[proxy.name]
            val (label, color) = when {
                proxy.enabled == false ->
                    stringResource(R.string.proxy_status_disabled) to StatusIdle
                adminItem?.status == "running" ->
                    stringResource(R.string.proxy_status_online) to StatusRunning
                adminItem?.status == "error" ->
                    stringResource(R.string.proxy_status_error) to StatusError
                adminItem != null -> adminItem.status to StatusIdle
                logState?.phase == LogProxyPhase.RUNNING ->
                    stringResource(R.string.proxy_status_online) to StatusRunning
                logState?.phase == LogProxyPhase.ERROR ->
                    stringResource(R.string.proxy_status_error) to StatusError
                logState?.phase == LogProxyPhase.PENDING ->
                    stringResource(R.string.proxy_status_pending) to StatusStarting
                state.phase == FrpcSupervisor.Phase.RUNNING ->
                    stringResource(R.string.proxy_status_unknown) to StatusIdle
                else -> stringResource(R.string.proxy_status_off) to StatusIdle
            }
            val proxyType = proxy.proxyType
            val remoteText = when {
                !adminItem?.remoteAddr.isNullOrBlank() ->
                    stringResource(R.string.proxy_remote_label) + " " + adminItem.remoteAddr
                proxyType?.needsRemotePort == true ->
                    if (proxy.remotePort == null || proxy.remotePort == 0) {
                        stringResource(R.string.proxy_remote_random)
                    } else {
                        stringResource(R.string.proxy_remote_label) + " :${proxy.remotePort}"
                    }
                proxyType?.domainBased == true ->
                    proxy.customDomains?.firstOrNull() ?: proxy.subdomain
                        ?: stringResource(R.string.proxy_remote_domain_missing)
                else -> stringResource(R.string.proxy_remote_private)
            }
            val errorText = adminItem?.err?.takeIf { it.isNotBlank() }
                ?: logState?.error?.takeIf { it.isNotBlank() }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = proxy.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = buildString {
                                append(proxy.type.uppercase())
                                append(" · ")
                                append(stringResource(R.string.proxy_local_label))
                                append(" ")
                                append(proxy.localIP ?: "127.0.0.1")
                                append(":")
                                append(proxy.localPort?.toString() ?: "?")
                                append(" → ")
                                append(remoteText)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (errorText != null) {
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    StatusChip(text = label, color = color)
                    Switch(
                        checked = proxy.enabled != false,
                        onCheckedChange = { on -> setRuleEnabled(proxy.name, on, isVisitor = false) },
                    )
                }
            }
        }

        if (visitors.isNotEmpty()) {
            item {
                GroupHeader(
                    text = stringResource(
                        R.string.home_group_count,
                        stringResource(R.string.home_visitors),
                        enabledVisitors.size,
                    ),
                )
            }
            items(visitors) { visitor ->
                val online = state.phase == FrpcSupervisor.Phase.RUNNING &&
                    visitor.name in visitorsOnline
                val (label, color) = when {
                    visitor.enabled == false ->
                        stringResource(R.string.proxy_status_disabled) to StatusIdle
                    online -> stringResource(R.string.proxy_status_online) to StatusRunning
                    state.phase == FrpcSupervisor.Phase.RUNNING ->
                        stringResource(R.string.proxy_status_pending) to StatusStarting
                    else -> stringResource(R.string.proxy_status_off) to StatusIdle
                }
                val listen = if (visitor.bindPort < 0) {
                    stringResource(R.string.visitor_no_listen)
                } else {
                    "${visitor.bindAddr}:${visitor.bindPort}"
                }
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
                            Text(text = visitor.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = buildString {
                                    append(visitor.type.uppercase())
                                    append(" · ")
                                    append(stringResource(R.string.proxy_local_label))
                                    append(" ")
                                    append(listen)
                                    append(" · ")
                                    append(stringResource(R.string.visitor_target_label))
                                    append(" ")
                                    append(target)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(text = label, color = color)
                        Switch(
                            checked = visitor.enabled != false,
                            onCheckedChange = { on -> setRuleEnabled(visitor.name, on, isVisitor = true) },
                        )
                    }
                }
            }
        }
    }

    switchTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text(stringResource(R.string.home_switch_config_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.home_switch_config_body,
                        currentProfile?.name.orEmpty(),
                        target.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        switchTarget = null
                        uiScope.launch {
                            container.settingsRepository.setDefaultProfileId(target.id)
                            context.startService(FrpcService.stopIntent(context))
                            withTimeoutOrNull(SWITCH_STOP_TIMEOUT_MS) {
                                FrpcSupervisor.state.first { !it.isActive }
                            }
                            val reason = withContext(Dispatchers.IO) {
                                FrpcSupervisor.checkBeforeStart(target)
                            }
                            if (reason == null) {
                                ContextCompat.startForegroundService(
                                    context,
                                    FrpcService.startIntent(context, target.id),
                                )
                            } else {
                                blockedReason = reason
                            }
                        }
                    },
                ) { Text(stringResource(R.string.home_switch_config_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { switchTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    blockedReason?.let { reason ->
        AlertDialog(
            onDismissRequest = { blockedReason = null },
            title = { Text(stringResource(R.string.start_block_title)) },
            text = {
                Text(
                    text = when (reason) {
                        FrpcSupervisor.StartBlockReason.NO_SERVER_ADDR ->
                            stringResource(R.string.start_block_no_server_addr)
                        FrpcSupervisor.StartBlockReason.ENGINE_MISSING ->
                            stringResource(R.string.start_block_engine_missing)
                        FrpcSupervisor.StartBlockReason.ADMIN_PORT_IN_USE ->
                            stringResource(
                                R.string.start_block_admin_port_in_use,
                                currentProfile?.config?.webServer?.port ?: 0,
                            )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        blockedReason = null
                        if (reason != FrpcSupervisor.StartBlockReason.ENGINE_MISSING) {
                            currentProfile?.let { navigator.push(Screen.ServerEdit(it.id)) }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (reason == FrpcSupervisor.StartBlockReason.ENGINE_MISSING) {
                                R.string.action_confirm
                            } else {
                                R.string.start_block_go_config
                            },
                        ),
                    )
                }
            },
        )
    }
}

private fun proxiesOf(profile: ServerProfile?): List<ProxyConfig> =
    profile?.config?.proxies ?: emptyList()

@Composable
private fun RowScope.StatCell(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
