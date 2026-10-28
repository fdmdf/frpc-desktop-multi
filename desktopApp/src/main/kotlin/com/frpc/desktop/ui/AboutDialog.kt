package com.frpc.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.frpc.desktop.AppContainer
import com.frpc.desktop.core.AppInfo
import com.frpc.desktop.core.AppPaths
import java.awt.Desktop
import java.net.URI

private const val REPO_URL = "https://github.com/fdmdf/frpc-desktop-multi"
private const val ISSUES_URL = "https://github.com/fdmdf/frpc-desktop-multi/issues"

/**
 * 关于对话框：托盘菜单里点「关于」时弹出（窗口隐藏时会先把主窗口显示出来，
 * 因为 Compose 的 Dialog 必须挂在一个已存在的窗口上）。
 */
@Composable
fun AboutDialog(container: AppContainer, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 380.dp, max = 460.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Frpc-Android-Multi", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "版本 ${AppInfo.version} · 内置引擎 frp ${container.engine.bundledVersion()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                LinkRow(label = "项目主页", value = REPO_URL)
                LinkRow(label = "问题反馈", value = ISSUES_URL)
                Text(
                    text = "配置与日志目录：${AppPaths.home.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "本程序是 frp 的图形外壳：内置 frpc 引擎，frp 自身以 Apache-2.0 许可发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { openUrl(value) }) { Text(label) }
        Text(
            text = value.removePrefix("https://"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}
