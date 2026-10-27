package com.frpc.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpc.android.R
import com.frpc.android.data.ServerProfile
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcSupervisor
import com.frpc.android.ui.components.SectionCard
import com.frpc.android.ui.components.StatusChip
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.android.ui.nav.Screen
import com.frpc.android.ui.theme.StatusRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConfigListScreen(container: AppContainer, navigator: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by container.configRepository.profiles.collectAsStateWithLifecycle()
    val defaultId by container.settingsRepository.defaultProfileId.collectAsStateWithLifecycle()
    val supervisorState by FrpcSupervisor.state.collectAsStateWithLifecycle()
    val pendingReloadId by FrpcSupervisor.pendingReloadProfileId.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var exportTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }
    val loadErrors by container.configRepository.loadErrors.collectAsStateWithLifecycle()

    // 导入：用 */* 才能选中 .toml / .conf 这类系统未登记 mime 的文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.ifBlank { null }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("无法读取文件")
                }
            }.onSuccess { text ->
                runCatching {
                    container.configRepository.importToml(fileName ?: "导入配置", text)
                }.onSuccess { profile ->
                    container.configRepository.upsert(profile)
                    // 仓库里只有这一份配置（或还没设默认）时，自动设为默认，避免"导入了但启动时找不到配置"
                    val onlyOne = container.configRepository.profiles.value.count { it.id != profile.id } == 0
                    val asDefault = onlyOne || container.settingsRepository.defaultProfileId.value == null
                    if (asDefault) {
                        container.settingsRepository.setDefaultProfileId(profile.id)
                    }
                    toast(
                        context,
                        context.getString(
                            if (asDefault) R.string.config_imported_as_default else R.string.config_imported,
                            profile.name,
                        ),
                    )
                }.onFailure { e -> importError = e.message ?: e.toString() }
            }.onFailure { e -> importError = e.message ?: e.toString() }
        }
    }

    // 导出到文件：走系统文件选择器，可存到“下载/文档”等任意位置
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        val payload = pendingSave
        pendingSave = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(payload.second.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入文件")
                }
            }.onSuccess {
                toast(context, context.getString(R.string.config_saved_to_file, payload.first))
            }.onFailure {
                toast(context, context.getString(R.string.config_export_failed, it.message ?: ""))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 底部留出操作条空间，避免最后一张卡片被遮挡
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loadErrors.isNotEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.config_load_errors_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        loadErrors.forEach { err ->
                            Text(
                                text = "· $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            text = stringResource(R.string.config_load_errors_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (profiles.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.home_no_config),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.config_import_hint))
                        }
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                val profileRunning = supervisorState.phase == FrpcSupervisor.Phase.RUNNING &&
                    supervisorState.profileId == profile.id
                ProfileCard(
                    profile = profile,
                    isDefault = profile.id == defaultId,
                    isRunning = profileRunning,
                    pendingReload = pendingReloadId == profile.id,
                    onEdit = {
                        container.configEditorStore.beginEdit(profile)
                        navigator.push(Screen.ServerEdit(profile.id))
                    },
                    onDuplicate = { scope.launch { container.configRepository.duplicate(profile.id) } },
                    onExport = { exportTarget = profile },
                    onSetDefault = {
                        scope.launch { container.settingsRepository.setDefaultProfileId(profile.id) }
                    },
                    onDelete = {
                        // 运行中的配置删掉会让 frpc 继续跑着一份已经不存在的配置
                        if (profileRunning) {
                            toast(context, context.getString(R.string.config_running_cannot_delete))
                        } else {
                            pendingDelete = profile
                        }
                    },
                )
            }
        }

        // 底部操作条：左侧导入（始终可用），右侧新建。
        // 让「新建配置」保持自身宽度、由左侧「导入」去吸收剩余空间，
        // 否则大字号/窄屏时被挤压的是主操作按钮
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = stringResource(R.string.action_import),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            ExtendedFloatingActionButton(
                onClick = {
                    container.configEditorStore.beginNew()
                    navigator.push(Screen.ServerEdit(null))
                },
                icon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.config_new),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }

    // 导出选项：保存到文件 / 分享 / 复制
    exportTarget?.let { profile ->
        val toml = container.configRepository.tomlOf(profile)
        val fileName = "${profile.name.ifBlank { "frpc" }}-frpc.toml"
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            title = { Text(stringResource(R.string.config_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            pendingSave = fileName to toml
                            exportTarget = null
                            saveLauncher.launch(fileName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.config_export_save_file))
                    }
                    TextButton(
                        onClick = {
                            exportTarget = null
                            shareToml(context, toml)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.config_export_share))
                    }
                    TextButton(
                        onClick = {
                            exportTarget = null
                            copyToClipboard(context, toml)
                            toast(context, context.getString(R.string.copied_to_clipboard))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.config_export_copy))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.config_delete_title)) },
            // 同名配置时只有名字没法区分（P5）：补上服务器摘要与代理数量
            text = {
                Text(
                    stringResource(
                        R.string.config_delete_confirm_detail,
                        profile.name,
                        "${profile.config.serverAddr}:${profile.config.serverPort}",
                        profile.config.proxies.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.configRepository.delete(profile.id) }
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.config_import_title)) },
            text = { Text(stringResource(R.string.config_import_failed, message)) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ServerProfile,
    isDefault: Boolean,
    isRunning: Boolean,
    /** 已保存但还没重载：运行中配置改完未生效的提示态 */
    pendingReload: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isRunning) {
                        StatusChip(
                            text = stringResource(R.string.config_running),
                            color = StatusRunning,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (pendingReload) {
                        StatusChip(
                            text = stringResource(R.string.config_pending_reload),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (isDefault) {
                        StatusChip(
                            text = stringResource(R.string.config_default),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(profile.serverSummary)
                        append(" · ")
                        append(stringResource(R.string.proxies_title))
                        append(" ")
                        append(profile.config.proxies.size)
                        if (profile.config.visitors.isNotEmpty()) {
                            append(" · ")
                            append(stringResource(R.string.visitors_title))
                            append(" ")
                            append(profile.config.visitors.size)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 运行中的配置允许编辑保存，但必须让人看到「还没生效」，
                // 否则容易以为存盘即生效（真正生效要靠「重载配置」）
                if (isRunning) {
                    Text(
                        text = if (pendingReload) {
                            stringResource(R.string.config_pending_reload_hint)
                        } else {
                            stringResource(R.string.config_running_edit_hint)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pendingReload) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.action_edit))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy)) },
                        onClick = { menuExpanded = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_export)) },
                        onClick = { menuExpanded = false; onExport() },
                    )
                    if (!isDefault) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.config_set_default)) },
                            onClick = { menuExpanded = false; onSetDefault() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("frpc.toml", text))
}

private fun shareToml(context: Context, toml: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, toml)
        putExtra(Intent.EXTRA_TITLE, "frpc.toml")
    }
    context.startActivity(Intent.createChooser(intent, "frpc.toml"))
}
