package com.frpc.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frpc.android.BuildConfig.VERSION_NAME
import com.frpc.android.R
import com.frpc.android.di.AppContainer
import com.frpc.android.ui.components.SectionCard
import com.frpc.android.ui.nav.AppNavigator

private const val REPO_URL = "https://github.com/fdmdf/frpc-desktop-multi"
private const val ISSUES_URL = "https://github.com/fdmdf/frpc-desktop-multi/issues"

/**
 * 关于页：版本、内置引擎、来源与许可。
 * 与参考项目一致把「关于」独立成页，而不是塞在设置里的一行小字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(container: AppContainer, navigator: AppNavigator) {
    val context = LocalContext.current
    val engineVersion = remember { container.binaryManager.bundledVersion() }
    val engineAbi = remember { container.binaryManager.deviceAbi() }
        ?: stringResource(R.string.unknown)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_about)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 注意：这里不能用 R.mipmap.ic_launcher。
                // 它是 <adaptive-icon> XML（mipmap-anydpi-v26），而 painterResource 只认 <vector>，
                // 解析到 adaptive-icon 会直接抛异常 —— 表现就是「点关于页就闪退」。
                // 因此用图标的前景矢量 + 自适应图标同款底色自己拼出来。
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colorResource(R.color.ic_launcher_background),
                    modifier = Modifier.size(72.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = stringResource(R.string.nav_about)) {
                Text(
                    text = stringResource(
                        R.string.about_version,
                        VERSION_NAME,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.about_engine, engineVersion, engineAbi),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                LinkRow(
                    label = stringResource(R.string.about_source),
                    url = REPO_URL,
                    context = context,
                )
                LinkRow(
                    label = stringResource(R.string.about_issues),
                    url = ISSUES_URL,
                    context = context,
                )
            }

            SectionCard {
                Text(
                    text = stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.about_frp_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, context: Context) {
    TextButton(
        onClick = { openUrl(context, url) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    Text(
        text = url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(intent) }
}
