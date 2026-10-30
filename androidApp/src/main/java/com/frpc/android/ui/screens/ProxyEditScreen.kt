package com.frpc.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpc.android.R
import com.frpc.android.di.AppContainer
import com.frpc.shared.net.ListeningPort
import com.frpc.shared.net.LocalPorts
import com.frpc.android.ui.components.DropdownField
import com.frpc.android.ui.components.LabeledTextField
import com.frpc.android.ui.components.NumberField
import com.frpc.android.ui.components.SectionCard
import com.frpc.android.ui.components.SwitchRow
import com.frpc.android.ui.components.formFieldKey
import com.frpc.android.ui.components.validationMessage
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.shared.model.AuthConfig
import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.ProxyPluginConfig
import com.frpc.shared.model.ProxyTransportConfig
import com.frpc.shared.model.ProxyType
import com.frpc.shared.validate.ConfigValidator
import com.frpc.shared.validate.Severity
import com.frpc.shared.validate.ValidationIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyEditScreen(
    container: AppContainer,
    navigator: AppNavigator,
    profileId: String,
    proxyIndex: Int,
) {
    val store = container.configEditorStore
    val proxy by store.proxyDraft.collectAsStateWithLifecycle()
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(proxyIndex) {
        if (store.hasProxyDraft(proxyIndex)) return@LaunchedEffect
        val existing = store.draft.value?.config?.proxies?.getOrNull(proxyIndex)
        store.beginProxyEdit(proxyIndex, existing)
    }

    fun goBack() {
        if (store.isProxyDirty()) {
            confirmDiscard = true
        } else {
            store.clearProxy()
            navigator.pop()
        }
    }
    androidx.activity.compose.BackHandler { goBack() }

    val current = proxy ?: return
    val localUser = store.draft.collectAsStateWithLifecycle().value?.config?.user
    val type = ProxyType.fromId(current.type)
    val errorFields = remember(issues) { issues.map { it.formFieldKey() }.toSet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.proxy_edit)) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val proxyIssues = ConfigValidator.validate(
                            FrpcConfig(
                                serverAddr = "validation",
                                auth = AuthConfig(token = "validation"),
                                proxies = listOf(current),
                            ),
                        ).filter { it.field.startsWith("proxies[") }
                        val blocking = proxyIssues.filter { it.severity == Severity.ERROR }
                        if (blocking.isNotEmpty()) {
                            issues = blocking
                        } else {
                            store.commitProxy(proxyIndex, current)
                            navigator.pop()
                        }
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (issues.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.validation_title)) {
                    issues.forEach { issue ->
                        Text(
                            text = "• " + validationMessage(issue),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.proxy_section_basic)) {
                LabeledTextField(
                    value = current.name,
                    onValueChange = { v -> store.updateProxy { it.copy(name = v) } },
                    label = requiredLabel(stringResource(R.string.field_proxy_name), true),
                    supportingText = stringResource(R.string.field_proxy_name_hint),
                    isError = "name" in errorFields,
                )
                DropdownField(
                    label = requiredLabel(stringResource(R.string.field_proxy_type), true),
                    selected = current.type,
                    options = ProxyType.entries.map { it.id },
                    optionLabel = { id -> proxyTypeLabel(id) },
                    onSelect = { v ->
                        store.updateProxy { it.copy(type = v).sanitizedForType() }
                    },
                )
                Text(
                    text = stringResource(
                        R.string.proxy_full_name,
                        com.frpc.shared.model.FrpcNaming.targetServerProxyName(
                            localUser, null, current.name.ifBlank { "…" },
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.field_proxy_enabled),
                    checked = current.enabled ?: true,
                    onCheckedChange = { v -> store.updateProxy { it.copy(enabled = v) } },
                    supportingText = stringResource(R.string.field_proxy_enabled_hint),
                )
            }

            if (type?.needsLocalAddr == true) {
                SectionCard(title = stringResource(R.string.section_local)) {
                    LabeledTextField(
                        value = current.localIP.orEmpty(),
                        onValueChange = { v -> store.updateProxy { it.copy(localIP = v) } },
                        label = requiredLabel(stringResource(R.string.field_local_ip), true),
                        supportingText = stringResource(R.string.field_local_ip_hint),
                        isError = "localIP" in errorFields,
                    )
                    NumberField(
                        value = (current.localPort ?: "").toString(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(localPort = v.toIntOrNull()) }
                        },
                        label = requiredLabel(stringResource(R.string.field_local_port), true),
                        isError = "localPort" in errorFields,
                        supportingText = if (type.id == "https") {
                            stringResource(R.string.field_local_port_https_hint)
                        } else {
                            null
                        },
                    )
                    var showPortPicker by remember { mutableStateOf(false) }
                    TextButton(onClick = { showPortPicker = true }) {
                        Text(stringResource(R.string.proxy_pick_local_port))
                    }
                    if (showPortPicker) {
                        LocalPortPickerDialog(
                            onDismiss = { showPortPicker = false },
                            onPick = { port ->
                                store.updateProxy { it.copy(localPort = port) }
                                showPortPicker = false
                            },
                        )
                    }
                }
            }

            if (type?.needsRemotePort == true) {
                SectionCard(title = stringResource(R.string.proxy_section_entry)) {
                    NumberField(
                        value = (current.remotePort ?: "").toString(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(remotePort = v.toIntOrNull()) }
                        },
                        label = stringResource(R.string.field_remote_port),
                        supportingText = stringResource(R.string.field_remote_port_hint),
                        isError = "remotePort" in errorFields,
                    )
                }
            }

            if (type?.domainBased == true) {
                SectionCard(title = stringResource(R.string.proxy_section_entry)) {
                    LabeledTextField(
                        value = current.subdomain.orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(subdomain = v.ifBlank { null }) }
                        },
                        label = stringResource(R.string.field_subdomain),
                        supportingText = stringResource(R.string.field_subdomain_hint),
                        isError = "subdomain" in errorFields,
                    )
                    LabeledTextField(
                        value = current.customDomains?.joinToString(",").orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy {
                                it.copy(
                                    customDomains = v.split(",")
                                        .map { d -> d.trim() }
                                        .filter { d -> d.isNotBlank() }
                                        .ifEmpty { null },
                                )
                            }
                        },
                        label = stringResource(R.string.field_custom_domains),
                        supportingText = stringResource(R.string.field_custom_domains_hint),
                        isError = "customDomains" in errorFields,
                    )
                }
            }

            if (type == ProxyType.TCPMUX) {
                SectionCard(title = stringResource(R.string.proxy_section_entry)) {
                    DropdownField(
                        label = stringResource(R.string.field_multiplexer),
                        selected = "httpconnect",
                        options = listOf("httpconnect"),
                        optionLabel = { it },
                        onSelect = { },
                    )
                    Text(
                        text = stringResource(R.string.field_multiplexer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (type?.supportsHttpAuth == true) {
                SectionCard(title = stringResource(R.string.proxy_section_access)) {
                    LabeledTextField(
                        value = current.httpUser.orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(httpUser = v.ifBlank { null }) }
                        },
                        label = stringResource(R.string.field_http_user),
                        supportingText = stringResource(R.string.field_http_user_hint),
                    )
                    LabeledTextField(
                        value = current.httpPassword.orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(httpPassword = v.ifBlank { null }) }
                        },
                        label = stringResource(R.string.field_http_password),
                    )
                    LabeledTextField(
                        value = current.routeByHTTPUser.orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(routeByHTTPUser = v.ifBlank { null }) }
                        },
                        label = stringResource(R.string.field_route_by_http_user),
                        supportingText = stringResource(R.string.field_route_by_http_user_hint),
                    )
                }
            }

            if (type?.supportsHttpAdvanced == true) {
                SectionCard(title = stringResource(R.string.section_routing)) {
                    LabeledTextField(
                        value = current.locations?.joinToString(",").orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy {
                                it.copy(
                                    locations = v.split(",")
                                        .map { d -> d.trim() }
                                        .filter { d -> d.isNotBlank() }
                                        .ifEmpty { null },
                                )
                            }
                        },
                        label = stringResource(R.string.field_locations),
                        supportingText = stringResource(R.string.field_locations_hint),
                    )
                    LabeledTextField(
                        value = current.hostHeaderRewrite.orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(hostHeaderRewrite = v.ifBlank { null }) }
                        },
                        label = stringResource(R.string.field_host_header_rewrite),
                        supportingText = stringResource(R.string.field_host_header_rewrite_hint),
                    )
                }
            }

            if (type?.needsSecretKey == true) {
                SectionCard(title = stringResource(R.string.proxy_section_access)) {
                    LabeledTextField(
                        value = current.secretKey.orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy { it.copy(secretKey = v.ifBlank { null }) }
                        },
                        label = requiredLabel(stringResource(R.string.field_secret_key), true),
                        supportingText = stringResource(R.string.field_secret_key_hint),
                        isError = "secretKey" in errorFields,
                    )
                    LabeledTextField(
                        value = current.allowUsers?.joinToString(",").orEmpty(),
                        onValueChange = { v ->
                            store.updateProxy {
                                it.copy(
                                    allowUsers = v.split(",")
                                        .map { u -> u.trim() }
                                        .filter { u -> u.isNotBlank() }
                                        .ifEmpty { null },
                                )
                            }
                        },
                        label = stringResource(R.string.field_allow_users),
                        supportingText = stringResource(R.string.field_allow_users_hint),
                    )
                }
            }

            SectionCard(title = stringResource(R.string.proxy_section_transport)) {
                LabeledTextField(
                    value = current.transport?.bandwidthLimit.orEmpty(),
                    onValueChange = { v ->
                        store.updateProxy {
                            it.copy(
                                transport = (it.transport ?: ProxyTransportConfig())
                                    .copy(bandwidthLimit = v.ifBlank { null }),
                            )
                        }
                    },
                    label = stringResource(R.string.field_bandwidth_limit),
                    placeholder = "1MB",
                    supportingText = stringResource(R.string.field_bandwidth_limit_hint),
                    isError = "transport.bandwidthLimit" in errorFields,
                )
                if (!current.transport?.bandwidthLimit.isNullOrBlank()) {
                    DropdownField(
                        label = stringResource(R.string.field_bandwidth_mode),
                        selected = current.transport?.bandwidthLimitMode ?: "client",
                        options = listOf("client", "server"),
                        optionLabel = {
                            stringResource(
                                if (it == "client") R.string.field_bandwidth_mode_client
                                else R.string.field_bandwidth_mode_server,
                            )
                        },
                        onSelect = { v ->
                            store.updateProxy {
                                it.copy(
                                    transport = (it.transport ?: ProxyTransportConfig())
                                        .copy(bandwidthLimitMode = v),
                                )
                            }
                        },
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.field_use_encryption),
                    checked = current.transport?.useEncryption ?: false,
                    onCheckedChange = { v ->
                        store.updateProxy {
                            it.copy(
                                transport = (it.transport ?: ProxyTransportConfig()).copy(useEncryption = v),
                            )
                        }
                    },
                    supportingText = stringResource(R.string.field_use_encryption_hint),
                )
                SwitchRow(
                    label = stringResource(R.string.field_use_compression),
                    checked = current.transport?.useCompression ?: false,
                    onCheckedChange = { v ->
                        store.updateProxy {
                            it.copy(
                                transport = (it.transport ?: ProxyTransportConfig()).copy(useCompression = v),
                            )
                        }
                    },
                    supportingText = stringResource(R.string.field_use_compression_hint),
                )
                if (type == ProxyType.HTTPS) {
                    DropdownField(
                        label = stringResource(R.string.field_proxy_protocol),
                        selected = current.transport?.proxyProtocolVersion ?: "",
                        options = listOf("", "v1", "v2"),
                        optionLabel = {
                            if (it.isEmpty()) stringResource(R.string.proxy_protocol_off) else it
                        },
                        onSelect = { v ->
                            store.updateProxy {
                                it.copy(
                                    transport = (it.transport ?: ProxyTransportConfig())
                                        .copy(proxyProtocolVersion = v.ifBlank { null }),
                                )
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.field_proxy_protocol_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (type == ProxyType.HTTPS) {
                val plugin = current.plugin
                SectionCard(title = stringResource(R.string.proxy_section_plugin)) {
                    DropdownField(
                        label = stringResource(R.string.field_plugin_type),
                        selected = plugin?.type?.takeIf { it == ProxyPluginConfig.TYPE_HTTPS2HTTP || it == ProxyPluginConfig.TYPE_TLS2RAW } ?: "",
                        options = listOf("", ProxyPluginConfig.TYPE_HTTPS2HTTP, ProxyPluginConfig.TYPE_TLS2RAW),
                        optionLabel = {
                            when (it) {
                                ProxyPluginConfig.TYPE_HTTPS2HTTP -> stringResource(R.string.field_plugin_https2http)
                                ProxyPluginConfig.TYPE_TLS2RAW -> stringResource(R.string.field_plugin_tls2raw)
                                else -> stringResource(R.string.field_plugin_none)
                            }
                        },
                        onSelect = { v ->
                            store.updateProxy {
                                it.copy(
                                    plugin = if (v.isBlank()) {
                                        null
                                    } else {
                                        ProxyPluginConfig(
                                            type = v,
                                            localAddr = plugin?.localAddr
                                                ?: "${it.localIP ?: "127.0.0.1"}:${it.localPort ?: ""}",
                                            crtPath = plugin?.crtPath,
                                            keyPath = plugin?.keyPath,
                                        )
                                    },
                                )
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.field_plugin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (plugin != null && (plugin.type == ProxyPluginConfig.TYPE_HTTPS2HTTP || plugin.type == ProxyPluginConfig.TYPE_TLS2RAW)) {
                        LabeledTextField(
                            value = plugin.localAddr.orEmpty(),
                            onValueChange = { v ->
                                store.updateProxy { it.copy(plugin = plugin.copy(localAddr = v.ifBlank { null })) }
                            },
                            label = stringResource(R.string.field_plugin_local_addr),
                            supportingText = stringResource(R.string.field_plugin_local_addr_hint),
                        )
                        LabeledTextField(
                            value = plugin.crtPath.orEmpty(),
                            onValueChange = { v ->
                                store.updateProxy { it.copy(plugin = plugin.copy(crtPath = v.ifBlank { null })) }
                            },
                            label = stringResource(R.string.field_plugin_crt),
                        )
                        LabeledTextField(
                            value = plugin.keyPath.orEmpty(),
                            onValueChange = { v ->
                                store.updateProxy { it.copy(plugin = plugin.copy(keyPath = v.ifBlank { null })) }
                            },
                            label = stringResource(R.string.field_plugin_key),
                        )
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    store.clearProxy()
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
}

@Composable
private fun requiredLabel(label: String, required: Boolean): String =
    if (required) "$label *" else label

@Composable
fun proxyTypeLabel(id: String): String = when (id) {
    "tcp" -> stringResource(R.string.proxy_type_tcp)
    "udp" -> stringResource(R.string.proxy_type_udp)
    "http" -> stringResource(R.string.proxy_type_http)
    "https" -> stringResource(R.string.proxy_type_https)
    "stcp" -> stringResource(R.string.proxy_type_stcp)
    "sudp" -> stringResource(R.string.proxy_type_sudp)
    "xtcp" -> stringResource(R.string.proxy_type_xtcp)
    "tcpmux" -> stringResource(R.string.proxy_type_tcpmux)
    else -> id
}

@Composable
private fun LocalPortPickerDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var ports by remember { mutableStateOf<List<ListeningPort>?>(null) }
    var scanToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(scanToken) {
        ports = null
        ports = withContext(Dispatchers.IO) { LocalPorts.list() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proxy_pick_local_port)) },
        text = {
            val list = ports
            when {
                list == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.proxy_scanning_ports),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                list.isEmpty() -> Text(stringResource(R.string.proxy_no_listening_ports))
                else -> LazyColumn {
                    items(list) { item ->
                        TextButton(
                            onClick = { onPick(item.port) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.protocol.uppercase(),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(text = "${item.host}:${item.port}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = {
            TextButton(onClick = { scanToken++ }) {
                Text(stringResource(R.string.action_rescan))
            }
        },
    )
}
