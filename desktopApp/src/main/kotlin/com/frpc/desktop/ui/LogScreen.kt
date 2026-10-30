package com.frpc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frpc.desktop.AppContainer
import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.engine.LogLevel
import com.frpc.desktop.engine.LogLine
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File

@Composable
fun LogScreen(container: AppContainer) {
    val logs by container.supervisor.logs.collectAsState()
    var level by remember { mutableStateOf<LogLevel?>(null) }
    var query by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf<String?>(null) }
    // 0 = frpc 连接日志，1 = 应用日志（启动/停止/重载/自动重启等，由 App 自己写）
    var tab by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    val filtered = remember(logs, level, query, tab) {
        logs.filter { line ->
            val byTab = if (tab == 0) line.tag != APP_TAG else line.tag == APP_TAG
            byTab &&
                (level == null || line.level == level) &&
                (query.isBlank() || line.message.contains(query, ignoreCase = true))
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            delay(60)
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("日志", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "显示 ${filtered.size} / 共 ${logs.size} 行 · 时间按本机时区",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.width(240.dp),
                )
                Button(onClick = {
                    val file = chooseFile("保存日志", FileDialog.SAVE, "frpc-log.txt") ?: return@Button
                    val target = if (file.extension.isNullOrBlank()) File(file.absolutePath + ".txt") else file
                    container.supervisor.saveLogs(target)
                        .onSuccess { saved = "已保存到 ${it.absolutePath}" }
                        .onFailure { saved = "保存失败：${it.message}" }
                }) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Text("保存", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = { container.supervisor.clearLogs() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                    Text("清空", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = {
                    runCatching {
                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(AppPaths.logsDir)
                    }
                }) { Text("日志目录") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = tab == 0,
                onClick = { tab = 0 },
                label = { Text("连接日志 ${logs.count { it.tag != APP_TAG }}") },
            )
            FilterChip(
                selected = tab == 1,
                onClick = { tab = 1 },
                label = { Text("应用日志 ${logs.count { it.tag == APP_TAG }}") },
            )
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(selected = level == null, onClick = { level = null }, label = { Text("全部") })
            FilterChip(
                selected = level == LogLevel.ERROR,
                onClick = { level = if (level == LogLevel.ERROR) null else LogLevel.ERROR },
                label = { Text("错误") },
            )
            FilterChip(
                selected = level == LogLevel.WARN,
                onClick = { level = if (level == LogLevel.WARN) null else LogLevel.WARN },
                label = { Text("警告") },
            )
            FilterChip(
                selected = level == LogLevel.INFO,
                onClick = { level = if (level == LogLevel.INFO) null else LogLevel.INFO },
                label = { Text("信息") },
            )
            FilterChip(
                selected = level == LogLevel.DEBUG,
                onClick = { level = if (level == LogLevel.DEBUG) null else LogLevel.DEBUG },
                label = { Text("调试") },
            )
            SwitchRow(
                label = "自动滚动",
                checked = autoScroll,
                onCheckedChange = { autoScroll = it },
                modifier = Modifier.padding(start = 16.dp).width(200.dp),
            )
            saved?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(filtered) { _, line -> LogRow(line) }
        }
    }
}

/** App 自己写入的日志行（启动/停止/重载/自动重启…）的 tag 标记 */
private const val APP_TAG = "app"

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> StatusStarting
        LogLevel.DEBUG, LogLevel.TRACE -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        MonoText(formatLogTime(line.timeMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoText(
            text = line.message,
            color = color,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
