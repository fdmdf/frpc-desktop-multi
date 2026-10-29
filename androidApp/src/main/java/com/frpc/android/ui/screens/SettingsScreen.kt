package com.frpc.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.frpc.android.BuildConfig.VERSION_NAME
import com.frpc.android.R
import com.frpc.android.data.AppLanguage
import com.frpc.android.data.ThemeMode
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcSupervisor
import com.frpc.android.ui.components.DropdownField
import com.frpc.android.ui.components.GroupCard
import com.frpc.android.ui.components.MonoText
import com.frpc.android.ui.components.RowDivider
import com.frpc.android.ui.components.SettingsRow
import com.frpc.android.ui.components.StatusChip
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.android.ui.nav.Screen
import com.frpc.android.ui.theme.StatusRunning
import kotlinx.coroutines.launch
@Composable
fun SettingsScreen(container: AppContainer, navigator: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle()
    val language by container.settingsRepository.language.collectAsStateWithLifecycle()
    val autoStart by container.settingsRepository.autoStartOnBoot.collectAsStateWithLifecycle()
    val defaultProfileId by container.settingsRepository.defaultProfileId.collectAsStateWithLifecycle()
    val showAccessLogs by container.settingsRepository.showAccessLogs.collectAsStateWithLifecycle()
    val profiles by container.configRepository.profiles.collectAsStateWithLifecycle()
    val state by FrpcSupervisor.state.collectAsStateWithLifecycle()

    // 清空全部配置是不可逆操作：先确认，运行中也禁止执行（配置正被 frpc 使用）
    var confirmResetAll by remember { mutableStateOf(false) }

    val powerManager = remember(context) { context.getSystemService<PowerManager>() }
    val batteryIgnored = remember(context) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    val engineVersion = remember { container.binaryManager.bundledVersion() }
    val engineAbi = FrpcSupervisor.state.value.abi ?: container.binaryManager.deviceAbi() ?: stringResource(R.string.unknown)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 外观：主题 + 语言。两项都是「点了就生效」的下拉，放进同一张卡片表示同类
        GroupCard(title = stringResource(R.string.settings_group_appearance)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DropdownField(
                    label = stringResource(R.string.settings_theme),
                    selected = themeMode,
                    options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                    optionLabel = { mode ->
                        when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                            ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            else -> mode
                        }
                    },
                    onSelect = { container.settingsRepository.setThemeMode(it) },
                )
                DropdownField(
                    label = stringResource(R.string.settings_language),
                    selected = language,
                    options = AppLanguage.ALL,
                    optionLabel = { tag ->
                        when (tag) {
                            AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
                            AppLanguage.EN -> stringResource(R.string.settings_language_en)
                            else -> stringResource(R.string.settings_language_system)
                        }
                    },
                    onSelect = { container.settingsRepository.setLanguage(it) },
                )
            }
        }

        // 启动：开关 + 默认配置。开关是主操作，默认配置是它的附属项，
        // 用行 + 分隔线放在一起，避免看起来是两个毫不相关的设置
        GroupCard(title = stringResource(R.string.settings_group_startup)) {
            SettingsRow(
                title = stringResource(R.string.settings_startup),
                subtitle = stringResource(R.string.settings_startup_summary),
                icon = Icons.Outlined.PowerSettingsNew,
                trailing = {
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { container.settingsRepository.setAutoStartOnBoot(it) },
                    )
                },
            )
            if (profiles.isNotEmpty()) {
                RowDivider()
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    DropdownField(
                        label = stringResource(R.string.settings_default_config),
                        selected = profiles.firstOrNull { it.id == defaultProfileId } ?: profiles.first(),
                        options = profiles,
                        optionLabel = { it.name },
                        onSelect = { container.settingsRepository.setDefaultProfileId(it.id) },
                    )
                }
            }
        }

        GroupCard(title = stringResource(R.string.settings_battery)) {
            SettingsRow(
                title = stringResource(R.string.settings_battery),
                subtitle = if (batteryIgnored) {
                    stringResource(R.string.settings_battery_ok)
                } else {
                    stringResource(R.string.settings_battery_summary)
                },
                icon = Icons.Outlined.BatterySaver,
                trailing = {
                    if (batteryIgnored) {
                        // 已达标就给一个「已完成」的状态片，而不是留一个点了没反应的按钮
                        StatusChip(
                            text = stringResource(R.string.settings_battery_ok_short),
                            color = StatusRunning,
                        )
                    } else {
                        // 次操作用 tonal 按钮：比文字链接更明确，又不抢主操作的视觉权重
                        FilledTonalButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                            Text(stringResource(R.string.settings_battery_request))
                        }
                    }
                },
            )
        }

        GroupCard(title = stringResource(R.string.nav_logs)) {
            SettingsRow(
                title = stringResource(R.string.settings_show_access_logs),
                subtitle = stringResource(R.string.settings_show_access_logs_summary),
                icon = Icons.Outlined.Article,
                trailing = {
                    Switch(
                        checked = showAccessLogs,
                        onCheckedChange = { container.settingsRepository.setShowAccessLogs(it) },
                    )
                },
            )
        }

        GroupCard(title = stringResource(R.string.settings_binary)) {
            val engineState = if (container.binaryManager.isReady()) {
                stringResource(R.string.settings_binary_ready)
            } else {
                stringResource(R.string.settings_binary_missing)
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_binary_version, engineVersion, engineAbi),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 运行时引擎实际路径：新版应为 /data/app/.../lib/arm64/libfrpc.so（jniLibs 解压位）
                MonoText(
                    text = stringResource(
                        R.string.settings_binary_path,
                        container.binaryManager.binaryFile().absolutePath,
                        engineState,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_binary_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        GroupCard(title = stringResource(R.string.settings_about)) {
            SettingsRow(
                title = stringResource(R.string.settings_about_open),
                subtitle = stringResource(
                    R.string.settings_about_summary,
                    VERSION_NAME,
                ),
                icon = Icons.Outlined.Info,
                onClick = { navigator.push(Screen.About) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }

        // 危险操作：整张卡片用 errorContainer 底色 + 红色标题，和上面的常规设置明确区分
        GroupCard(
            title = stringResource(R.string.settings_group_danger),
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        ) {
            SettingsRow(
                title = stringResource(R.string.action_reset_all),
                subtitle = if (state.isActive) {
                    stringResource(R.string.settings_reset_all_blocked)
                } else {
                    stringResource(R.string.reset_all_message, profiles.size)
                },
                icon = Icons.Outlined.DeleteForever,
                onClick = { confirmResetAll = true },
                enabled = profiles.isNotEmpty() && !state.isActive,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text(stringResource(R.string.reset_all_title)) },
            text = { Text(stringResource(R.string.reset_all_message, profiles.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmResetAll = false
                        scope.launch {
                            container.configRepository.clearAll()
                            container.settingsRepository.setDefaultProfileId(null)
                            Toast.makeText(context, context.getString(R.string.reset_all_done), Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
