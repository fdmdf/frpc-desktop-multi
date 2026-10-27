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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(container: AppContainer, navigator: AppNavigator) {
    val context = LocalContext.current
    val state by FrpcSupervisor.state.collectAsStateWithLifecycle()
    val status by FrpcSupervisor.status.collectAsStateWithLifecycle()
    val logProxyStates by FrpcSupervisor.logProxyStates.collectAsStateWithLifecycle()
    val visitorsOnline by FrpcSupervisor.visitors.collectAsStateWithLifecycle()
    val stats by FrpcSupervisor.stats.collectAsStateWithLifecycle()
    val cpu by FrpcSupervisor.cpuPercent.collectAsStateWithLifecycle()
    val adminReachable by FrpcSupervisor.adminReachable.collectAsStateWithLifecycle()
    val serverConnected by FrpcSupervisor.serverConnected.collectAsStateWithLifecycle()
    val loginError by FrpcSupervisor.loginError.collectAsStateWithLifecycle()
    // 运行中改了配置但只存盘没重载：frpc 还在用旧配置，这里必须给个出口
    val pendingReloadId by FrpcSupervisor.pendingReloadProfileId.collectAsStateWithLifecycle()
    val profiles by container.configRepository.profiles.collectAsStateWithLifecycle()
    val defaultId by container.settingsRepository.defaultProfileId.collectAsStateWithLifecycle()

    // 未设默认配置时回退到第一个，避免"有配置却显示还没有配置"
    val currentProfileId = state.profileId ?: defaultId ?: profiles.firstOrNull()?.id
    val currentProfile = profiles.firstOrNull { it.id == currentProfileId }
    val uiScope = rememberCoroutineScope()

    // 启动前自检被拦下时弹出原因，并引导去配置页（引擎缺失只能重装，不给跳转）
    var blockedReason by remember { mutableStateOf<FrpcSupervisor.StartBlockReason?>(null) }
    // 自检本身在后台线程做，期间先把按钮按住，避免连点重复拉起服务
    var checking by remember { mutableStateOf(false) }

    // 运行时长秒级刷新
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase) {
        while (FrpcSupervisor.state.value.phase == FrpcSupervisor.Phase.RUNNING) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptime = state.startedAtMs?.let { formatDuration(nowMs - it) } ?: "--"
    val startedAtText = state.startedAtMs?.let { formatClock(it) }

    // 进程活着但一直登录失败：不能只显示「运行中」（P4）
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
    // 禁用的规则 frpc 不注册，既不占在线数也不该进分母（否则永远显示 0/1）
    val enabledProxies = proxies.filter { it.enabled != false }
    // 在线数优先取 Admin API，其次日志推断
    val onlineCount = status?.all?.count { it.status == "running" }
        ?: logProxyStates.count { it.value.phase == LogProxyPhase.RUNNING }
    val totalCount = enabledProxies.size

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
                    // 状态页（本机浏览器）入口提示：frp 的面板要登录，账号是配置里的面板用户名/密码
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
                    // 端口探测涉及连/绑 socket，必须在后台做，别占主线程
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

        // 「已保存但还没重载」：不提示的话，用户会以为改完就生效了
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

        // 配置切换（多个配置时）
        if (profiles.size > 1) {
            item {
                SectionCard(title = stringResource(R.string.home_current_config)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 全部列出（外层可横向滚动）：之前 take(4) 会让第 5 份配置在主页选不到
                        profiles.forEach { profile ->
                            FilterChip(
                                selected = profile.id == currentProfileId,
                                onClick = {
                                    uiScope.launch {
                                        container.settingsRepository.setDefaultProfileId(profile.id)
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

        // 分组标题用普通文字，不再用「空标题卡片」——空卡片会渲染成一个只有标题的方框
        // 计数只算已启用的：禁用的规则不注册到 frps，混进总数会让「0/1 在线」看起来像故障
        item {
            GroupHeader(
                text = if (proxies.isEmpty()) {
                    stringResource(R.string.home_proxies)
                } else {
                    stringResource(
                        R.string.home_group_count,
                        stringResource(R.string.home_proxies),
                        totalCount,
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
            // 状态优先级：enabled=false（frpc 不注册）> Admin API 实际状态 > 日志推断 > 未知
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
            // 只有 tcp/udp 有远程端口；remotePort 留空或 0 都表示 frps 随机分配
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
                }
            }
        }

        if (visitors.isNotEmpty()) {
            item {
                GroupHeader(
                    text = stringResource(
                        R.string.home_group_count,
                        stringResource(R.string.home_visitors),
                        visitors.size,
                    ),
                )
            }
            items(visitors) { visitor ->
                // admin API 不返回 visitor，是否注册成功只能从 "visitor added: [...]" 日志判断
                val online = state.phase == FrpcSupervisor.Phase.RUNNING &&
                    visitor.name in visitorsOnline
                val (label, color) = when {
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
                // 显示 frps 上实际查找的名字（含对方设备前缀 / 本机前缀），跨设备时才好排查
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
                                    append(" → ")
                                    append(target)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(text = label, color = color)
                    }
                }
            }
        }
    }

    // 启动前自检未通过：说清原因并给出下一步，而不是让 frpc 默默退出后被自动重启掩盖
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
                        // 引擎缺失只能重装应用，跳配置页没有意义
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
    // weight(1f)：四个格子等分宽度，长数值（如内存/时长）只会自身省略，不会把邻居挤出屏幕
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
