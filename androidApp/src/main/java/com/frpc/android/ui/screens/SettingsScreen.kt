package com.frpc.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.frpc.android.R
import com.frpc.android.data.AppLanguage
import com.frpc.android.data.ThemeMode
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcSupervisor
import com.frpc.android.ui.components.DropdownField
import com.frpc.android.ui.components.MonoText
import com.frpc.android.ui.components.SectionCard
import com.frpc.android.ui.components.SwitchRow
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.android.ui.nav.Screen
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
        SectionCard(title = stringResource(R.string.settings_theme)) {
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
        }

        SectionCard(title = stringResource(R.string.settings_language)) {
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

        SectionCard(title = stringResource(R.string.settings_startup)) {
            SwitchRow(
                label = stringResource(R.string.settings_startup),
                checked = autoStart,
                onCheckedChange = { container.settingsRepository.setAutoStartOnBoot(it) },
                supportingText = stringResource(R.string.settings_startup_summary),
            )
            if (profiles.isNotEmpty()) {
                DropdownField(
                    label = stringResource(R.string.settings_default_config),
                    selected = profiles.firstOrNull { it.id == defaultProfileId } ?: profiles.first(),
                    options = profiles,
                    optionLabel = { it.name },
                    onSelect = { container.settingsRepository.setDefaultProfileId(it.id) },
                )
            }
        }

        SectionCard(title = stringResource(R.string.settings_battery)) {
            Text(
                text = if (batteryIgnored) {
                    stringResource(R.string.settings_battery_ok)
                } else {
                    stringResource(R.string.settings_battery_summary)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!batteryIgnored) {
                TextButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                    Text(stringResource(R.string.settings_battery_request))
                }
            }
        }

        SectionCard(title = stringResource(R.string.nav_logs)) {
            SwitchRow(
                label = stringResource(R.string.settings_show_access_logs),
                checked = showAccessLogs,
                onCheckedChange = { container.settingsRepository.setShowAccessLogs(it) },
                supportingText = stringResource(R.string.settings_show_access_logs_summary),
            )
        }

        SectionCard(title = stringResource(R.string.settings_binary)) {
            val engineState = if (container.binaryManager.isReady()) {
                stringResource(R.string.settings_binary_ready)
            } else {
                stringResource(R.string.settings_binary_missing)
            }
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(
                text = stringResource(R.string.settings_about_version, com.frpc.android.BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "frpc $engineVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { navigator.push(Screen.About) }) {
                Text(stringResource(R.string.nav_about))
            }
        }

        // 危险操作：清空全部配置档案。运行中禁止，避免删掉 frpc 正在读的配置
        SectionCard {
            TextButton(
                onClick = { confirmResetAll = true },
                enabled = profiles.isNotEmpty() && !state.isActive,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.action_reset_all))
            }
            Text(
                text = if (state.isActive) {
                    stringResource(R.string.settings_reset_all_blocked)
                } else {
                    stringResource(R.string.reset_all_message, profiles.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
