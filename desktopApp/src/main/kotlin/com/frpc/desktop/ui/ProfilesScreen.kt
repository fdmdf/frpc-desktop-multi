package com.frpc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frpc.desktop.AppContainer
import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.engine.FrpcSupervisor
import com.frpc.shared.model.ServerProfile
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun ProfilesScreen(
    container: AppContainer,
    openProfileId: String? = null,
    onOpenHandled: () -> Unit = {},
) {
    val profiles by container.profiles.profiles.collectAsState()
    val defaultId by container.settings.state.collectAsState()
    val supervisorState by container.supervisor.state.collectAsState()
    // 运行中的配置允许编辑保存，改动要等「重载配置」才生效（见列表里的提示文案）
    val runningId = supervisorState.profileId
        .takeIf { supervisorState.phase == FrpcSupervisor.Phase.RUNNING }
    val pendingReloadId by container.supervisor.pendingReloadProfileId.collectAsState()
    val scope = rememberCoroutineScope()

    var editingId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 从主页「编辑配置」跳进来时直接打开对应配置（运行中的也可以，保存时会问是否重载）
    LaunchedEffect(openProfileId) {
        if (openProfileId != null) {
            editingId = openProfileId
            onOpenHandled()
        }
    }

    if (creating) {
        ProfileEditScreen(
            container = container,
            initial = null,
            onClose = { creating = false },
        )
        return
    }
    editingId?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        if (profile != null) {
            ProfileEditScreen(
                container = container,
                initial = profile,
                onClose = { editingId = null },
            )
            return
        }
    }

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
                Text("服务器配置", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "每份配置对应一个 frps；导入的 frpc.toml 会按代理类型清理掉多余字段",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val file = chooseFile("导入 frpc.toml", FileDialog.LOAD) ?: return@OutlinedButton
                    scope.launch {
                        runCatching {
                            container.profiles.importToml(
                                file.nameWithoutExtension.ifBlank { "导入配置" },
                                file.readText(),
                            )
                        }.onSuccess { profile ->
                            container.profiles.upsert(profile)
                            if (container.profiles.profiles.value.size == 1 ||
                                container.settings.state.value.defaultProfileId == null
                            ) {
                                container.settings.setDefaultProfileId(profile.id)
                            }
                            message = "已导入「${profile.name}」"
                        }.onFailure { message = "导入失败：${it.message}" }
                    }
                }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Text("导入", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("新建配置", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        // 搜索 + 备份：配置多了以后，翻列表找一份比导入还慢
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索配置") },
                placeholder = { Text("名称 / 服务器地址") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                val file = chooseFile("导出全部配置", FileDialog.SAVE, "frpcdash-profiles.json")
                    ?: return@OutlinedButton
                runCatching { file.writeText(container.profiles.exportAll()) }
                    .onSuccess { message = "已导出 ${profiles.size} 份配置到 ${file.absolutePath}" }
                    .onFailure { message = "导出失败：${it.message}" }
            }) { Text("导出全部") }
            OutlinedButton(onClick = {
                val file = chooseFile("导入全部配置", FileDialog.LOAD) ?: return@OutlinedButton
                scope.launch {
                    container.profiles.importAll(file.readText(), replace = false)
                        .onSuccess { s ->
                            message = "已导入：新增 ${s.added} 份、更新 ${s.updated} 份" +
                                if (s.skipped.isNotEmpty()) "，跳过 ${s.skipped.size} 条（${s.skipped.joinToString("、")}）" else ""
                            // 导入后没有默认配置时补一个，否则主页空着
                            if (container.settings.state.value.defaultProfileId == null) {
                                container.profiles.profiles.value.firstOrNull()?.let {
                                    container.settings.setDefaultProfileId(it.id)
                                }
                            }
                        }
                        .onFailure { message = "导入失败：${it.message}" }
                }
            }) { Text("导入全部") }
            TextButton(onClick = { clearAllConfirm = true }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Text("清空全部", modifier = Modifier.padding(start = 4.dp))
            }
        }

        val loadErrors by container.profiles.loadErrors.collectAsState()
        if (loadErrors.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "部分配置无法加载，已跳过：",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    loadErrors.forEach { err ->
                        Text(
                            "· $err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        message?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { message = null }) { Text("关闭") }
                }
            }
        }

        val visible = profiles.filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.serverSummary.contains(query, ignoreCase = true)
        }

        if (profiles.isEmpty()) {
            SectionCard {
                Text("还没有任何配置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点右上角「新建配置」从零填，或「导入」现成的 frpc.toml",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (visible.isEmpty()) {
            SectionCard {
                Text(
                    "没有匹配「$query」的配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        visible.forEach { profile ->
            val isDefault = profile.id == defaultId.defaultProfileId
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            if (profile.id == runningId) {
                                StatusChip(
                                    text = "运行中",
                                    color = StatusRunning,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            // 运行中改完没重载：还有一笔改动没生效，标出来
                            if (profile.id == pendingReloadId) {
                                StatusChip(
                                    text = "待重载",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            if (isDefault) {
                                StatusChip(
                                    text = "默认",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Text(
                            text = "${profile.serverSummary} · 代理 ${profile.config.proxies.size} 个" +
                                if (profile.config.visitors.isNotEmpty()) " · 访问者 ${profile.config.visitors.size} 个" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 运行中的配置允许编辑保存，但要把「保存 ≠ 生效」说清楚
                        if (profile.id == runningId) {
                            Text(
                                text = if (profile.id == pendingReloadId) {
                                    "已保存，尚未重载，仍在使用旧配置"
                                } else {
                                    "运行中，保存后需「重载配置」才生效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (profile.id == pendingReloadId) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(onClick = { editingId = profile.id }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { scope.launch { container.profiles.duplicate(profile.id) } }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = {
                        val file = chooseFile("导出 frpc.toml", FileDialog.SAVE, "${profile.name}.toml")
                            ?: return@IconButton
                        runCatching { file.writeText(container.profiles.tomlOf(profile)) }
                            .onSuccess { message = "已导出到 ${file.absolutePath}" }
                            .onFailure { message = "导出失败：${it.message}" }
                    }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "导出")
                    }
                    IconButton(
                        onClick = { container.settings.setDefaultProfileId(profile.id) },
                        enabled = !isDefault,
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = "设为默认")
                    }
                    IconButton(
                        onClick = {
                            if (profile.id == runningId) {
                                message = "「${profile.name}」正在运行，请先停止再删除"
                            } else {
                                deleteTarget = profile
                            }
                        },
                        enabled = profile.id != runningId,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
        }
        Box(modifier = Modifier.padding(bottom = 8.dp))
    }

    if (clearAllConfirm) {
        AlertDialog(
            onDismissRequest = { clearAllConfirm = false },
            title = { Text("清空全部配置") },
            text = {
                Text(
                    "将删除 ${profiles.size} 份配置（含代理与访问者规则），并停止正在运行的服务。\n" +
                        "该操作不可撤销——建议先点「导出全部」备份。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearAllConfirm = false
                    scope.launch {
                        container.supervisor.stop()
                        container.profiles.clearAll()
                        container.settings.setDefaultProfileId(null)
                        message = "已清空全部配置"
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearAllConfirm = false }) { Text("取消") } },
        )
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除配置") },
            // 同名配置时只有名字没法区分（P5）：补上服务器摘要与代理数量
            text = {
                Text(
                    "确定删除「${profile.name}」（${profile.serverSummary}，" +
                        "代理 ${profile.config.proxies.size} 个）？该操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.profiles.delete(profile.id)
                        if (container.settings.state.value.defaultProfileId == profile.id) {
                            container.settings.setDefaultProfileId(
                                container.profiles.profiles.value.firstOrNull { it.id != profile.id }?.id,
                            )
                        }
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 系统文件选择器（AWT，跨平台，无需额外依赖） */
fun chooseFile(title: String, mode: Int, defaultName: String? = null): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (defaultName != null) dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}

fun defaultConfigDir(): File = AppPaths.home
