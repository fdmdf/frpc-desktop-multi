package com.frpc.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpc.android.R
import com.frpc.android.di.AppContainer
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
import com.frpc.shared.model.FrpcNaming
import com.frpc.shared.validate.ConfigValidator
import com.frpc.shared.validate.Severity
import com.frpc.shared.validate.ValidationIssue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitorEditScreen(
    container: AppContainer,
    navigator: AppNavigator,
    profileId: String,
    visitorIndex: Int,
) {
    val store = container.configEditorStore
    val visitor by store.visitorDraft.collectAsStateWithLifecycle()
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // 防重入：旋转/重绘时若草稿已属于该下标则跳过初始化（避免丢失未保存输入）
    LaunchedEffect(visitorIndex) {
        if (store.hasVisitorDraft(visitorIndex)) return@LaunchedEffect
        val existing = store.draft.value?.config?.visitors?.getOrNull(visitorIndex)
        store.beginVisitorEdit(visitorIndex, existing)
    }

    // 返回 = 放弃：不保存就必须丢掉草稿，否则下次进来看到的还是上次没保存的内容
    fun goBack() {
        if (store.isVisitorDirty()) {
            confirmDiscard = true
        } else {
            store.clearVisitor()
            navigator.pop()
        }
    }
    androidx.activity.compose.BackHandler { goBack() }

    val current = visitor ?: return
    val errorFields = remember(issues) { issues.map { it.formFieldKey() }.toSet() }
    // frp 的拼接规则：serverUser 非空用它，留空用本机 user；这里实时展示最终会查找的名字
    val localUser = store.draft.collectAsStateWithLifecycle().value?.config?.user
    val targetName = FrpcNaming.targetServerProxyName(
        localUser, current.serverUser, current.serverName,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visitor_edit)) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        // 复用 shared 层校验器（单访问者临时配置），必填项不满足则阻止保存
                        val visitorIssues = ConfigValidator.validate(
                            FrpcConfig(
                                serverAddr = "validation",
                                auth = AuthConfig(token = "validation"),
                                visitors = listOf(current),
                            ),
                        ).filter { it.field.startsWith("visitors[") }
                        val blocking = visitorIssues.filter { it.severity == Severity.ERROR }
                        if (blocking.isNotEmpty()) {
                            issues = blocking
                        } else {
                            store.commitVisitor(visitorIndex, current)
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

            SectionCard(title = stringResource(R.string.visitors_title)) {
                Text(
                    text = stringResource(R.string.visitor_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledTextField(
                    value = current.name,
                    onValueChange = { v -> store.updateVisitor { it.copy(name = v) } },
                    label = stringResource(R.string.field_visitor_name) + " *",
                    supportingText = stringResource(R.string.field_visitor_name_hint),
                    isError = "name" in errorFields,
                )
                DropdownField(
                    label = stringResource(R.string.field_visitor_type) + " *",
                    selected = current.type,
                    options = listOf("stcp", "sudp", "xtcp"),
                    optionLabel = { id -> visitorTypeLabel(id) },
                    onSelect = { v ->
                        // 切类型时清掉不适用字段（keepTunnelOpen 仅 xtcp 有）
                        store.updateVisitor { it.copy(type = v).sanitizedForType() }
                    },
                )
                LabeledTextField(
                    value = current.serverName,
                    onValueChange = { v -> store.updateVisitor { it.copy(serverName = v) } },
                    label = stringResource(R.string.field_server_name) + " *",
                    supportingText = stringResource(R.string.field_server_name_hint),
                    isError = "serverName" in errorFields,
                )
                Text(
                    text = stringResource(R.string.visitor_target_preview, targetName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                FrpcNaming.splitPrefixedName(current.serverName)?.let { (prefix, rest) ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.visitor_target_suggestion, prefix, rest),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            // 一键拆成「对方前缀 + 代理名」，这样本机用户名前缀就不会被拼进去
                            store.updateVisitor { it.copy(serverUser = prefix, serverName = rest) }
                        }) {
                            Text(stringResource(R.string.visitor_target_apply_fix))
                        }
                    }
                }
                SwitchRow(
                    label = stringResource(R.string.field_visitor_enabled),
                    checked = current.enabled ?: true,
                    onCheckedChange = { v -> store.updateVisitor { it.copy(enabled = v) } },
                    supportingText = stringResource(R.string.field_visitor_enabled_hint),
                )
                LabeledTextField(
                    value = current.secretKey.orEmpty(),
                    onValueChange = { v ->
                        store.updateVisitor { it.copy(secretKey = v.ifBlank { null }) }
                    },
                    label = stringResource(R.string.field_secret_key),
                    supportingText = stringResource(R.string.field_secret_key_hint),
                )
                LabeledTextField(
                    value = current.serverUser.orEmpty(),
                    onValueChange = { v ->
                        store.updateVisitor { it.copy(serverUser = v.ifBlank { null }) }
                    },
                    label = stringResource(R.string.field_visitor_server_user),
                    supportingText = if (localUser.isNullOrBlank()) {
                        stringResource(R.string.field_visitor_server_user_hint_none)
                    } else {
                        stringResource(R.string.field_visitor_server_user_hint, localUser)
                    },
                )
            }
            SectionCard(title = stringResource(R.string.visitor_section_listen)) {
                LabeledTextField(
                    value = current.bindAddr,
                    onValueChange = { v -> store.updateVisitor { it.copy(bindAddr = v) } },
                    label = stringResource(R.string.field_bind_addr) + " *",
                    supportingText = stringResource(R.string.field_bind_addr_hint),
                    isError = "bindAddr" in errorFields,
                )
                NumberField(
                    value = current.bindPort.toString(),
                    onValueChange = { v ->
                        // 解析失败给 0：让校验器明确报「不能为 0」，不静默塞默认值
                        store.updateVisitor { it.copy(bindPort = v.trim().toIntOrNull() ?: 0) }
                    },
                    label = stringResource(R.string.field_bind_port) + " *",
                    supportingText = stringResource(R.string.field_bind_port_hint),
                    isError = "bindPort" in errorFields,
                    allowNegative = true,
                )
                if (current.type == "xtcp") {
                    Text(
                        text = stringResource(R.string.visitor_xtcp_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (current.type == "xtcp") {
                SectionCard(title = stringResource(R.string.visitor_section_xtcp)) {
                    SwitchRow(
                        label = stringResource(R.string.field_keep_tunnel_open),
                        checked = current.keepTunnelOpen ?: false,
                        onCheckedChange = { v ->
                            store.updateVisitor { it.copy(keepTunnelOpen = v) }
                        },
                        supportingText = stringResource(R.string.field_keep_tunnel_open_hint),
                    )
                    LabeledTextField(
                        value = current.fallbackTo.orEmpty(),
                        onValueChange = { v ->
                            store.updateVisitor { it.copy(fallbackTo = v.ifBlank { null }) }
                        },
                        label = stringResource(R.string.field_fallback_to),
                        supportingText = stringResource(R.string.field_fallback_to_hint),
                    )
                    NumberField(
                        value = current.fallbackTimeoutMs?.toString().orEmpty(),
                        onValueChange = { v ->
                            store.updateVisitor { it.copy(fallbackTimeoutMs = v.toIntOrNull()) }
                        },
                        label = stringResource(R.string.field_fallback_timeout),
                        supportingText = stringResource(R.string.field_fallback_timeout_hint),
                    )
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
                    store.clearVisitor()
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

/** 访问者类型的中文说明（复用代理类型的文案资源） */
@Composable
private fun visitorTypeLabel(id: String): String = when (id) {
    "stcp" -> stringResource(R.string.proxy_type_stcp)
    "sudp" -> stringResource(R.string.proxy_type_sudp)
    "xtcp" -> stringResource(R.string.proxy_type_xtcp)
    else -> id
}
