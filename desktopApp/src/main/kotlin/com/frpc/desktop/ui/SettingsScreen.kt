package com.frpc.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frpc.desktop.AppContainer
import com.frpc.desktop.core.AppInfo
import com.frpc.desktop.core.AppPaths
import java.awt.Desktop
import java.awt.FileDialog

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.state.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    var engineMessage by remember { mutableStateOf<String?>(null) }
    // 自定义引擎路径属于排障用的高级选项，默认收起，避免误填
    var showEngineAdvanced by remember { mutableStateOf(false) }
    // 提前算好，避免在字符串模板里嵌 lambda（Kotlin 解析会报错）
    val defaultProfileName: String? = profiles.firstOrNull { it.id == settings.defaultProfileId }?.name
        ?: profiles.firstOrNull()?.name

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = "外观") {
            DropdownField(
                label = "主题",
                selected = settings.theme,
                options = listOf("system", "light", "dark"),
                optionLabel = { mode ->
                    when (mode) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    }
                },
                onSelect = { v -> container.settings.update { it.copy(theme = v) } },
            )
        }

        SectionCard(title = "启动") {
            SwitchRow(
                label = "启动应用时自动运行默认配置",
                checked = settings.autoStartOnLaunch,
                onCheckedChange = { v -> container.settings.update { it.copy(autoStartOnLaunch = v) } },
                supportingText = if (defaultProfileName == null) "还没有任何配置" else "当前默认：$defaultProfileName",
            )
            SwitchRow(
                label = "关闭窗口时最小化到托盘",
                checked = settings.closeToTray,
                onCheckedChange = { v -> container.settings.update { it.copy(closeToTray = v) } },
                supportingText = "关闭后 frpc 继续运行，可从托盘图标重新打开窗口或退出",
            )
        }

        SectionCard(title = "日志") {
            SwitchRow(
                label = "显示状态接口访问日志",
                checked = settings.showAccessLogs,
                onCheckedChange = { v -> container.settings.update { it.copy(showAccessLogs = v) } },
                supportingText = "状态页每 4 秒刷新会产生两条日志，默认隐藏",
            )
        }

        SectionCard(title = "frpc 引擎") {
            Text(
                text = "内置版本：${container.engine.bundledVersion()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "当前使用：${container.engine.engineFile().absolutePath}" +
                    if (container.engine.isUsingCustom()) "（自定义）" else "（内置）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showEngineAdvanced = !showEngineAdvanced }) {
                Text(if (showEngineAdvanced) "收起高级选项" else "高级：自定义引擎路径")
            }
            if (showEngineAdvanced) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabeledTextField(
                        value = settings.enginePath,
                        onValueChange = { v -> container.settings.update { it.copy(enginePath = v) } },
                        label = "自定义引擎路径（留空用内置）",
                        placeholder = "C:\\tools\\frpc.exe",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        val file = chooseFile("选择 frpc 可执行文件", FileDialog.LOAD) ?: return@OutlinedButton
                        container.settings.update { it.copy(enginePath = file.absolutePath) }
                    }) { Text("浏览") }
                    Button(onClick = {
                        engineMessage = container.engine.ensureReady()
                            .map { "引擎就绪：${it.absolutePath}" }
                            .getOrElse { "引擎不可用：${it.message}" }
                    }) { Text("检查引擎") }
                }
                engineMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { openPath(AppPaths.home) }) { Text("打开数据目录") }
                TextButton(onClick = { openPath(container.engine.engineFile().parentFile) }) { Text("打开引擎目录") }
                TextButton(onClick = { openPath(AppPaths.runDir) }) { Text("打开运行目录") }
            }
        }

        SectionCard(title = "关于") {
            Text(
                text = "FrpcDash ${AppInfo.version}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "内置引擎 frp ${container.engine.bundledVersion()} · 配置文件 ${AppPaths.home.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.padding(bottom = 8.dp)) {}
    }
}

private fun openPath(file: java.io.File?) {
    if (file == null) return
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    }
}
