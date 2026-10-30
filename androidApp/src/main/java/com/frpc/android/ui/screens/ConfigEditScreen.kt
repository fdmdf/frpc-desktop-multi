package com.frpc.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpc.android.R
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcService
import com.frpc.android.service.FrpcSupervisor
import com.frpc.android.ui.components.DropdownField
import com.frpc.android.ui.components.GroupHeader
import com.frpc.android.ui.components.LabeledTextField
import com.frpc.android.ui.components.NumberField
import com.frpc.android.ui.components.SectionCard
import com.frpc.android.ui.components.SwitchRow
import com.frpc.android.ui.components.validationMessage
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.android.ui.nav.Screen
import com.frpc.android.util.isPortInUse
import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.LogConfig
import com.frpc.shared.model.TransportConfig
import com.frpc.shared.model.WebServerConfig
import com.frpc.shared.validate.ValidationIssue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditScreen(
    container: AppContainer,
    navigator: AppNavigator,
    profileId: String?,
) {
    val store = container.configEditorStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by store.draft.collectAsStateWithLifecycle()
    val supervisorState by FrpcSupervisor.state.collectAsStateWithLifecycle()
    // 正在运行的配置允许直接编辑，保存后自动重载；不再要求先停止服务
    val editingRunning = supervisorState.phase == FrpcSupervisor.Phase.RUNNING &&
        supervisorState.profileId == profileId
    var showValidation by remember { mutableStateOf<List<ValidationIssue>?>(null) }
    // 服务器端口的输入中间态：清空时不能跳回 7000（与桌面端一致的 P2 修复）
    var serverPortText by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // 运行中保存：先问「要不要立刻重载」，别默默存盘让用户以为已经生效
    var confirmSaveRunning by remember { mutableStateOf(false) }

    // 防重入：从代理/访问者编辑页返回时本屏幕会重新进入组合，
    // 若草稿已属于当前配置则跳过初始化，否则整个草稿（含已添加的代理）会被清空
    LaunchedEffect(profileId) {
        if (store.hasDraft(profileId)) return@LaunchedEffect
        if (profileId == null) {
            store.beginNew()
        } else {
            container.configRepository.find(profileId)?.let { store.beginEdit(it) }
        }
    }

    // 不保存就退出 = 放弃整份草稿：否则下次进这份配置看到的还是上次没保存的修改
    fun goBack() {
        if (store.isConfigDirty()) {
            confirmDiscard = true
        } else {
            store.clear()
            navigator.pop()
        }
    }
    androidx.activity.compose.BackHandler { goBack() }

    val current = draft ?: return
    val config = current.config

    // 警告不阻止保存，但要一直可见（例如 xtcp 未填 STUN：能启动，但打洞大概率超时）
    val warnings = remember(config) {
        com.frpc.shared.validate.ConfigValidator.validate(config)
            .filter { it.severity == com.frpc.shared.validate.Severity.WARNING }
    }

    /**
     * 落盘 + 收尾。
     *
     * 运行中的配置也允许保存（旧做法是整页禁用编辑，改个端口都得先停服务），
     * 但 frpc 手里拿的还是旧配置：
     * - reload = true：保存后立刻重载，配置马上生效，清掉「待重载」标记
     * - reload = false：只存盘，打上「待重载」标记，由主页/列表页提示用户还有一笔没生效的改动
     */
    fun doSave(reload: Boolean) {
        serverPortText?.let { t -> store.update { it.copy(serverPort = t.toIntOrNull() ?: 0) } }
        nameError = null
        val issues = store.validate(::isPortInUse)
        if (issues.any { it.severity == com.frpc.shared.validate.Severity.ERROR }) {
            showValidation = issues
            return
        }
        val profile = store.toProfile() ?: return
        val dup = container.configRepository.profiles.value.any {
            it.name == profile.name.trim() && it.id != profile.id
        }
        if (dup) {
            // 同名配置无法区分，直接拦下（P5）
            nameError = context.getString(R.string.error_duplicate_profile_name, profile.name.trim())
            showValidation = null
            return
        }
        scope.launch {
            container.configRepository.upsert(profile)
            if (container.settingsRepository.defaultProfileId.value == null) {
                container.settingsRepository.setDefaultProfileId(profile.id)
            }
            store.clear()
            if (editingRunning) {
                if (reload) {
                    FrpcSupervisor.clearPendingReload()
                    context.startService(FrpcService.reloadIntent(context))
                } else {
                    FrpcSupervisor.markPendingReload(profile.id)
                }
            }
            navigator.pop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (current.isNew) R.string.config_new else R.string.config_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        // 运行中的配置：先问清楚要不要顺手重载，不要默默存盘
                        if (editingRunning) confirmSaveRunning = true else doSave(reload = false)
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            nameError?.let { msg ->
                item {
                    SectionCard {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            // 运行中编辑：不拦着改，但把「保存 ≠ 生效」写在最上面，避免误判
            if (editingRunning) {
                item {
                    SectionCard(title = stringResource(R.string.config_running_editing_title)) {
                        Text(
                            text = stringResource(R.string.config_running_editing_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (warnings.isNotEmpty()) {
                item {
                    SectionCard(title = stringResource(R.string.validation_warnings_title)) {
                        warnings.forEach { issue ->
                            Text(
                                text = "• " + validationMessage(issue),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.section_server)) {
                    LabeledTextField(
                        value = current.name,
                        onValueChange = { store.rename(it) },
                        label = stringResource(R.string.config_name),
                        supportingText = stringResource(R.string.config_name_hint),
                    )
                    LabeledTextField(
                        value = config.serverAddr,
                        onValueChange = { v -> store.update { it.copy(serverAddr = v) } },
                        label = stringResource(R.string.field_server_addr),
                        placeholder = "frps.example.com",
                        supportingText = stringResource(R.string.field_server_addr_hint),
                    )
                    NumberField(
                        value = serverPortText ?: config.serverPort.toString(),
                        onValueChange = { v ->
                            serverPortText = v
                            v.toIntOrNull()?.let { p -> store.update { it.copy(serverPort = p) } }
                        },
                        label = stringResource(R.string.field_server_port),
                        supportingText = stringResource(R.string.field_server_port_hint),
                    )
                    LabeledTextField(
                        value = config.user.orEmpty(),
                        onValueChange = { v -> store.update { it.copy(user = v.trim().ifBlank { null }) } },
                        label = stringResource(R.string.field_user),
                        placeholder = stringResource(R.string.field_user_placeholder),
                        supportingText = stringResource(R.string.field_user_hint),
                    )
                    LabeledTextField(
                        value = config.clientID.orEmpty(),
                        onValueChange = { v -> store.update { it.copy(clientID = v.trim().ifBlank { null }) } },
                        label = stringResource(R.string.field_client_id),
                        supportingText = stringResource(R.string.field_client_id_hint),
                    )
                    SwitchRow(
                        label = stringResource(R.string.field_login_fail_exit),
                        checked = config.loginFailExit,
                        onCheckedChange = { v -> store.update { it.copy(loginFailExit = v) } },
                        supportingText = stringResource(R.string.field_login_fail_exit_hint),
                    )
                    LabeledTextField(
                        value = config.natHoleStunServer.orEmpty(),
                        onValueChange = { v -> store.update { it.copy(natHoleStunServer = v.ifBlank { null }) } },
                        label = stringResource(R.string.field_stun_server),
                        placeholder = "stun.easyvoip.com:3478",
                        supportingText = stringResource(R.string.field_stun_server_hint),
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.section_auth)) {
                    // 无认证 = auth 为空 / method = "none"；编码时整段省略（frpc 不接受 none）
                    val auth = config.auth ?: com.frpc.shared.model.AuthConfig(method = "none", token = "")
                    DropdownField(
                        label = stringResource(R.string.field_auth_method),
                        selected = auth.method,
                        options = listOf("token", "none"),
                        optionLabel = {
                            if (it == "token") {
                                stringResource(R.string.field_auth_method_token)
                            } else {
                                stringResource(R.string.field_auth_method_none)
                            }
                        },
                        onSelect = { v ->
                            store.update {
                                it.copy(
                                    auth = (it.auth ?: com.frpc.shared.model.AuthConfig()).copy(method = v),
                                )
                            }
                        },
                    )
                    if (auth.method == "none") {
                        Text(
                            text = stringResource(R.string.field_auth_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LabeledTextField(
                            value = auth.token,
                            onValueChange = { v ->
                                store.update {
                                    it.copy(auth = (it.auth ?: com.frpc.shared.model.AuthConfig()).copy(token = v))
                                }
                            },
                            label = stringResource(R.string.field_auth_token),
                            supportingText = stringResource(R.string.field_auth_token_hint),
                        )
                    }
                    LabeledTextField(
                        value = config.metadatas?.get("token").orEmpty(),
                        onValueChange = { v ->
                            // frps 的 multiuser 插件按 metadatas.token 识别用户；空则整段不写
                            store.update {
                                it.copy(metadatas = v.trim().ifBlank { null }?.let { t -> mapOf("token" to t) })
                            }
                        },
                        label = stringResource(R.string.field_metadatas_token),
                        supportingText = stringResource(R.string.field_metadatas_token_hint),
                    )
                    Text(
                        text = stringResource(R.string.field_auth_oidc_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.section_admin)) {
                    SwitchRow(
                        label = stringResource(R.string.field_admin_enabled),
                        checked = config.webServer != null,
                        onCheckedChange = { enabled ->
                            store.update {
                                it.copy(
                                    webServer = if (enabled) {
                                        it.webServer ?: WebServerConfig()
                                    } else {
                                        null
                                    },
                                )
                            }
                        },
                        supportingText = stringResource(R.string.field_admin_enabled_hint),
                    )
                    config.webServer?.let { ws ->
                        LabeledTextField(
                            value = ws.addr,
                            onValueChange = { v -> store.update { it.copy(webServer = ws.copy(addr = v)) } },
                            label = stringResource(R.string.field_admin_addr),
                        )
                        NumberField(
                            value = ws.port.toString(),
                            onValueChange = { v ->
                                store.update { it.copy(webServer = ws.copy(port = v.toIntOrNull() ?: 7400)) }
                            },
                            label = stringResource(R.string.field_admin_port),
                            supportingText = stringResource(R.string.field_admin_port_hint),
                        )
                        LabeledTextField(
                            value = ws.user,
                            onValueChange = { v -> store.update { it.copy(webServer = ws.copy(user = v)) } },
                            label = stringResource(R.string.field_admin_user),
                        )
                        LabeledTextField(
                            value = ws.password,
                            onValueChange = { v -> store.update { it.copy(webServer = ws.copy(password = v)) } },
                            label = stringResource(R.string.field_admin_password),
                            supportingText = stringResource(R.string.field_admin_password_hint),
                        )
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.section_transport)) {
                    DropdownField(
                        label = stringResource(R.string.field_transport_protocol),
                        selected = config.transport?.protocol ?: "tcp",
                        options = listOf("tcp", "kcp", "quic", "websocket", "wss"),
                        optionLabel = { it },
                        onSelect = { v ->
                            store.update {
                                it.copy(transport = (it.transport ?: TransportConfig()).copy(protocol = v))
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.transport_protocol_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow(
                        label = stringResource(R.string.field_tcp_mux),
                        checked = config.transport?.tcpMux ?: true,
                        onCheckedChange = { v ->
                            store.update {
                                it.copy(transport = (it.transport ?: TransportConfig()).copy(tcpMux = v))
                            }
                        },
                        supportingText = stringResource(R.string.field_tcp_mux_hint),
                    )
                    NumberField(
                        value = (config.transport?.poolCount ?: 0).toString(),
                        onValueChange = { v ->
                            store.update {
                                it.copy(transport = (it.transport ?: TransportConfig()).copy(poolCount = v.toIntOrNull() ?: 0))
                            }
                        },
                        label = stringResource(R.string.field_pool_count),
                        supportingText = stringResource(R.string.field_pool_count_hint),
                    )
                    SwitchRow(
                        label = stringResource(R.string.field_tls_enable),
                        checked = config.transport?.tls?.enable ?: true,
                        onCheckedChange = { v ->
                            store.update {
                                val transport = it.transport ?: TransportConfig()
                                it.copy(transport = transport.copy(tls = (transport.tls ?: com.frpc.shared.model.TlsConfig()).copy(enable = v)))
                            }
                        },
                        supportingText = stringResource(R.string.field_tls_hint),
                    )
                    // 心跳 / 多路复用保活：留空表示不写入，沿用 frpc 默认值（10 / 90 / 30 秒）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(
                            value = config.transport?.heartbeatInterval?.toString().orEmpty(),
                            onValueChange = { v ->
                                store.update {
                                    it.copy(transport = (it.transport ?: TransportConfig()).copy(heartbeatInterval = v.toIntOrNull()))
                                }
                            },
                            label = stringResource(R.string.field_heartbeat_interval),
                            supportingText = stringResource(R.string.field_optional_default_hint),
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = config.transport?.heartbeatTimeout?.toString().orEmpty(),
                            onValueChange = { v ->
                                store.update {
                                    it.copy(transport = (it.transport ?: TransportConfig()).copy(heartbeatTimeout = v.toIntOrNull()))
                                }
                            },
                            label = stringResource(R.string.field_heartbeat_timeout),
                            supportingText = stringResource(R.string.field_optional_default_hint),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    NumberField(
                        value = config.transport?.tcpMuxKeepaliveInterval?.toString().orEmpty(),
                        onValueChange = { v ->
                            store.update {
                                it.copy(transport = (it.transport ?: TransportConfig()).copy(tcpMuxKeepaliveInterval = v.toIntOrNull()))
                            }
                        },
                        label = stringResource(R.string.field_tcp_mux_keepalive),
                        supportingText = stringResource(R.string.field_optional_default_hint),
                    )
                    Text(
                        text = stringResource(R.string.field_tls_cert_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LabeledTextField(
                        value = config.transport?.tls?.certFile.orEmpty(),
                        onValueChange = { v ->
                            store.update {
                                val t = it.transport ?: TransportConfig()
                                it.copy(
                                    transport = t.copy(
                                        tls = (t.tls ?: com.frpc.shared.model.TlsConfig()).copy(
                                            certFile = v.trim().ifBlank { null },
                                        ),
                                    ),
                                )
                            }
                        },
                        label = stringResource(R.string.field_tls_cert_file),
                        placeholder = "/data/user/0/.../client.crt",
                    )
                    LabeledTextField(
                        value = config.transport?.tls?.keyFile.orEmpty(),
                        onValueChange = { v ->
                            store.update {
                                val t = it.transport ?: TransportConfig()
                                it.copy(
                                    transport = t.copy(
                                        tls = (t.tls ?: com.frpc.shared.model.TlsConfig()).copy(
                                            keyFile = v.trim().ifBlank { null },
                                        ),
                                    ),
                                )
                            }
                        },
                        label = stringResource(R.string.field_tls_key_file),
                    )
                    LabeledTextField(
                        value = config.transport?.tls?.trustedCaFile.orEmpty(),
                        onValueChange = { v ->
                            store.update {
                                val t = it.transport ?: TransportConfig()
                                it.copy(
                                    transport = t.copy(
                                        tls = (t.tls ?: com.frpc.shared.model.TlsConfig()).copy(
                                            trustedCaFile = v.trim().ifBlank { null },
                                        ),
                                    ),
                                )
                            }
                        },
                        label = stringResource(R.string.field_tls_ca_file),
                    )
                    SwitchRow(
                        label = stringResource(R.string.field_disable_tls_first_byte),
                        // frp 默认 disableCustomTLSFirstByte = true（不发送），开关默认关闭
                        checked = config.transport?.tls?.disableCustomTLSFirstByte == false,
                        onCheckedChange = { on ->
                            store.update {
                                val t = it.transport ?: TransportConfig()
                                it.copy(
                                    transport = t.copy(
                                        tls = (t.tls ?: com.frpc.shared.model.TlsConfig()).copy(
                                            disableCustomTLSFirstByte = !on,
                                        ),
                                    ),
                                )
                            }
                        },
                        supportingText = stringResource(R.string.field_disable_tls_first_byte_hint),
                    )
                    // UDP 包大小属于传输层（影响 udp/sudp 分片），原来误放在日志区
                    LabeledTextField(
                        value = (config.udpPacketSize ?: 1500).toString(),
                        onValueChange = { v ->
                            store.update { it.copy(udpPacketSize = v.toIntOrNull()) }
                        },
                        label = stringResource(R.string.field_udp_packet_size),
                        supportingText = stringResource(R.string.field_udp_packet_size_hint),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                    LabeledTextField(
                        value = config.transport?.proxyURL.orEmpty(),
                        onValueChange = { v ->
                            store.update {
                                it.copy(transport = (it.transport ?: TransportConfig()).copy(proxyURL = v.ifBlank { null }))
                            }
                        },
                        label = stringResource(R.string.field_proxy_url),
                        placeholder = "http://user:pass@host:port",
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.section_log)) {
                    DropdownField(
                        label = stringResource(R.string.field_log_level),
                        selected = config.log?.level ?: "info",
                        options = listOf("trace", "debug", "info", "warn", "error"),
                        optionLabel = { it },
                        onSelect = { v ->
                            store.update {
                                it.copy(log = (it.log ?: LogConfig()).copy(level = v))
                            }
                        },
                    )
                    NumberField(
                        value = (config.log?.maxDays ?: 3).toString(),
                        onValueChange = { v ->
                            store.update {
                                it.copy(log = (it.log ?: LogConfig()).copy(maxDays = v.toIntOrNull() ?: 3))
                            }
                        },
                        label = stringResource(R.string.field_log_max_days),
                        supportingText = stringResource(R.string.field_optional_default_hint),
                    )
                    Text(
                        text = stringResource(R.string.field_log_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 代理规则
            item {
                SectionHeaderRow(
                    title = stringResource(
                        R.string.home_group_count,
                        stringResource(R.string.proxies_title),
                        config.proxies.size,
                    ),
                    onAdd = {
                        navigator.push(Screen.ProxyEdit(current.profileId ?: "", config.proxies.size))
                    },
                    addDescription = stringResource(R.string.proxy_new),
                )
            }
            if (config.proxies.isEmpty()) {
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
            itemsIndexedProxies(config, onEdit = { index ->
                navigator.push(Screen.ProxyEdit(current.profileId ?: "", index))
            }, onDelete = { index ->
                store.update {
                    it.copy(proxies = it.proxies.filterIndexed { i, _ -> i != index })
                }
            }, onToggleEnabled = { index, enabled ->
                store.update {
                    it.copy(
                        proxies = it.proxies.mapIndexed { i, p ->
                            if (i == index) p.copy(enabled = enabled) else p
                        },
                    )
                }
            })

            // 访问者
            item {
                SectionHeaderRow(
                    title = stringResource(
                        R.string.home_group_count,
                        stringResource(R.string.visitors_title),
                        config.visitors.size,
                    ),
                    onAdd = {
                        navigator.push(Screen.VisitorEdit(current.profileId ?: "", config.visitors.size))
                    },
                    addDescription = stringResource(R.string.visitor_new),
                )
            }
            if (config.visitors.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.visitors_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexedVisitors(config, onEdit = { index ->
                navigator.push(Screen.VisitorEdit(current.profileId ?: "", index))
            }, onDelete = { index ->
                store.update {
                    it.copy(visitors = it.visitors.filterIndexed { i, _ -> i != index })
                }
            }, onToggleEnabled = { index, enabled ->
                store.update {
                    it.copy(
                        visitors = it.visitors.mapIndexed { i, v ->
                            if (i == index) v.copy(enabled = enabled) else v
                        },
                    )
                }
            })
        }
    }

    showValidation?.let { issues ->
        AlertDialog(
            onDismissRequest = { showValidation = null },
            title = { Text(stringResource(R.string.validation_title)) },
            text = {
                Column {
                    issues.forEach { issue ->
                        Text(
                            text = "• " + validationMessage(issue),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showValidation = null }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    store.clear()
                    navigator.pop()
                }) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.action_keep_editing))
                }
            },
        )
    }

    // 运行中保存的三岔口：立刻重载 / 只存盘 / 取消
    if (confirmSaveRunning) {
        AlertDialog(
            onDismissRequest = { confirmSaveRunning = false },
            title = { Text(stringResource(R.string.config_save_running_title)) },
            text = { Text(stringResource(R.string.config_save_running_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSaveRunning = false
                    doSave(reload = true)
                }) {
                    Text(stringResource(R.string.action_save_and_reload))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmSaveRunning = false
                        doSave(reload = false)
                    }) {
                        Text(stringResource(R.string.action_save_only))
                    }
                    TextButton(onClick = { confirmSaveRunning = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

/** 代理列表行 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedProxies(
    config: FrpcConfig,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onToggleEnabled: (Int, Boolean) -> Unit,
) {
    itemsIndexed(config.proxies) { index, proxy ->
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = proxy.enabled ?: true
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = proxy.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = proxy.type.uppercase() + " · " +
                            (proxy.localIP ?: "127.0.0.1") + ":" + (proxy.localPort?.toString() ?: "?") +
                            if (enabled) "" else " · " + stringResource(R.string.proxy_status_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 列表内直接启停，不必进编辑页翻开关：禁用的代理 frpc 会跳过注册
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggleEnabled(index, it) },
                )
                IconButton(onClick = { onEdit(index) }) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                }
                IconButton(onClick = { onDelete(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedVisitors(
    config: FrpcConfig,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onToggleEnabled: (Int, Boolean) -> Unit,
) {
    itemsIndexed(config.visitors) { index, visitor ->
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = visitor.enabled ?: true
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = visitor.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = visitor.type.uppercase() + " · " +
                            stringResource(R.string.visitor_target_label) + " " + visitor.serverName +
                            if (enabled) "" else " · " + stringResource(R.string.proxy_status_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 与代理规则一致：列表内直接启停，禁用的访问者 frpc 不会去注册
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggleEnabled(index, it) },
                )
                IconButton(onClick = { onEdit(index) }) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                }
                IconButton(onClick = { onDelete(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
        }
    }
}

/** 分组标题 + 右侧新增按钮：比「标题卡片 + 空内容」更紧凑，也避免出现空盒子 */
@Composable
private fun SectionHeaderRow(title: String, onAdd: () -> Unit, addDescription: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupHeader(text = title)
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = addDescription)
        }
    }
}
