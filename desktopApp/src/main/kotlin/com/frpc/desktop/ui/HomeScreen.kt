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
import com.frpc.shared.format.formatDateTime
import com.frpc.shared.format.formatDuration
import com.frpc.shared.format.formatMemoryKb
import com.frpc.shared.format.formatPercent
import com.frpc.shared.model.FrpcNaming
import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.ServerProfile
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
    // 运行中改了配置但只存盘没重载：frpc 还在用旧配置，这里必须给个出口
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

    // 启动前自检被拦下时弹出原因（对齐参考项目的 B1001 / B1006 拦截）
    var blockedReason by remember { mutableStateOf<String?>(null) }
    // 运行中选了别的配置：先确认，再「停止当前 → 用新配置启动」，不能只改默认配置
    var switchTarget by remember { mutableStateOf<ServerProfile?>(null) }

    // 运行时长每秒刷新
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
    // 计数只算已启用的：禁用的规则不注册，混进总数会让「0/1 在线」看起来像故障（与 Android 端一致）
    val enabledProxies = proxies.filter { it.enabled != false }
    val enabledVisitors = visitors.filter { it.enabled != false }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }

    // 行内启停：只改 enabled 一个字段，其余仍受「运行中不可编辑」约束。
    // 代理与访问者共用这一条路径，避免两端行为分叉
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
            // 运行中改 enabled 必须让 frpc 重读配置，否则只是文件变了、进程没变
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
                            // 运行中不能只改默认：首页会显示 A、frpc 还在跑 B，两边分叉最难排查
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
                    // 进程活着但一直登录失败：不能只显示「运行中」，用户会被误导（P4）
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

            // P4：登录失败要在主页直接可见，而不是只有日志页能翻到
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

        // 「已保存但还没重载」：不提示的话，用户会以为改完就生效了
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
                        // 运行中拿 frpc 真正加载的那份配置，未运行时按当前显示的配置算
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
                // enabled = false 的代理 frpc 根本不会注册，不该显示「未知/未运行」让人以为没生效
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
            // 目标端展示按类型区分：只有 tcp/udp 有「远程端口」，stcp/xtcp 只有访问者能连，
            // remotePort = 0 与留空一样表示由 frps 随机分配（不能显示成 ":0"）
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
                    // 行内启停：运行中改完立刻 reload，不用进编辑页
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
            // admin API 不返回 visitor，注册成功与否只能从 "visitor added: [...]" 日志判断
            val online = running && visitor.name in visitorsOnline
            // 与代理一致：enabled = false 的访问者 frpc 根本不会注册，不该显示「启动中」
            val (label, color) = when {
                visitor.enabled == false -> "已禁用" to StatusIdle
                online -> "在线" to StatusRunning
                running -> "启动中" to StatusStarting
                else -> "未运行" to StatusIdle
            }
            val listen = if (visitor.bindPort < 0) "不监听端口" else "${visitor.bindAddr}:${visitor.bindPort}"
            // 卡片上显示 frps 实际查找的名字（含 serverUser / 本机 user 前缀），跨设备时才好排查
            val target = FrpcNaming.targetServerProxyName(
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
                    // 行内启停：与代理一致，改完立刻 reload
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

    // 运行中切换配置：必须让 frpc 真正换过去，只改默认会让「显示的」和「跑着的」分叉
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
                            // 先等旧进程退出再自检：它还占着状态接口端口时，
                            // checkBeforeStart 会把「自己占的端口」误报成端口冲突
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

    // 启动前自检未通过：给出可读原因，并可直接跳到配置页修正，而不是只在日志里留一行
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
    proxies: List<ProxyConfig>,
    effectiveTotal: Int,
    totalFromAdmin: Int?,
    onlineFromAdmin: Int?,
): String {
    // 没有状态接口（admin 关闭）时按生效配置算：
    // 一是排除已禁用规则（frpc 不注册它们，算进分母就是永远凑不齐的 0/1），
    // 二是用 frpc 真正加载的那份配置，否则禁用后未重载时会出现「在线 3 / 总 2」
    val total = totalFromAdmin ?: effectiveTotal
    val online = onlineFromAdmin ?: 0
    return if (total > 0) "$online/$total" else "--"
}
