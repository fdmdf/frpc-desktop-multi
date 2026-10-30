package com.frpc.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpc.android.R
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcLogParser
import com.frpc.android.service.FrpcSupervisor
import com.frpc.android.service.LogLevel
import com.frpc.android.ui.components.MonoText
import com.frpc.android.util.formatLogTime
import kotlinx.coroutines.delay

@Composable
fun LogScreen(container: AppContainer) {
    val logs by FrpcSupervisor.logs.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val filtered = remember(logs, levelFilter) {
        logs.filter { levelFilter == null || it.level == levelFilter }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            delay(80)
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 过滤器较多，窄屏/大字号下必须可横向滚动，否则末尾的「调试」会被挤掉
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text(stringResource(R.string.log_filter_all)) },
            )
            FilterChip(
                selected = levelFilter == LogLevel.ERROR,
                onClick = { levelFilter = if (levelFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                label = { Text(stringResource(R.string.log_filter_error)) },
            )
            FilterChip(
                selected = levelFilter == LogLevel.WARN,
                onClick = { levelFilter = if (levelFilter == LogLevel.WARN) null else LogLevel.WARN },
                label = { Text(stringResource(R.string.log_filter_warn)) },
            )
            FilterChip(
                selected = levelFilter == LogLevel.INFO,
                onClick = { levelFilter = if (levelFilter == LogLevel.INFO) null else LogLevel.INFO },
                label = { Text(stringResource(R.string.log_filter_info)) },
            )
            FilterChip(
                selected = levelFilter == LogLevel.DEBUG,
                onClick = { levelFilter = if (levelFilter == LogLevel.DEBUG) null else LogLevel.DEBUG },
                label = { Text(stringResource(R.string.log_filter_debug)) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { autoScroll = !autoScroll }) {
                Text(
                    stringResource(R.string.log_autoscroll) + if (autoScroll) " ✓" else "",
                )
            }
            TextButton(onClick = { FrpcSupervisor.clearLogs() }) {
                Text(stringResource(R.string.log_clear))
            }
            TextButton(onClick = {
                val text = FrpcSupervisor.logsSnapshot().joinToString("\n") { line ->
                    "${formatLogTime(line.timeMs)} [${line.level.name.first()}] ${line.message}"
                }
                clipboard.setText(AnnotatedString(text))
            }) {
                Text(stringResource(R.string.action_share))
            }
            Text(
                text = stringResource(R.string.log_lines, filtered.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp),
            )
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.log_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered.size) { index ->
                    val line = filtered[index]
                    val color = when (line.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        LogLevel.DEBUG, LogLevel.TRACE -> MaterialTheme.colorScheme.onSurfaceVariant
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MonoText(
                            text = formatLogTime(line.timeMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MonoText(
                            text = line.message,
                            color = color,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
