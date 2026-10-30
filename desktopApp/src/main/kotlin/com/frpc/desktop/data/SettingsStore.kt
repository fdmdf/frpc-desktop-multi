package com.frpc.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SettingsData(
    val theme: String = "system",
    val enginePath: String = "",
    val showAccessLogs: Boolean = false,
    val defaultProfileId: String? = null,
    val autoStartOnLaunch: Boolean = false,
    val closeToTray: Boolean = true,
)

class SettingsStore(private val file: File) {
    private val json by lazy { Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true } }
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsData> = _state.asStateFlow()

    private fun read(): SettingsData {
        if (!file.exists()) return SettingsData()
        val text = runCatching { file.readText() }.getOrNull() ?: return SettingsData()
        return runCatching { parse(text) }
            .recoverCatching { json.decodeFromString<SettingsData>(text) }
            .getOrDefault(SettingsData())
    }

    private fun parse(text: String): SettingsData {
        val default = SettingsData()
        fun str(key: String): String? = field(text, key)?.let { raw ->
            if (raw == "null") null
            else raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        fun bool(key: String): Boolean? = field(text, key)?.takeIf { it == "true" || it == "false" }?.toBoolean()
        return SettingsData(
            theme = str(KEY_THEME) ?: default.theme,
            enginePath = str(KEY_ENGINE_PATH) ?: default.enginePath,
            showAccessLogs = bool(KEY_SHOW_ACCESS_LOGS) ?: default.showAccessLogs,
            defaultProfileId = str(KEY_DEFAULT_PROFILE) ?: default.defaultProfileId,
            autoStartOnLaunch = bool(KEY_AUTO_START) ?: default.autoStartOnLaunch,
            closeToTray = bool(KEY_CLOSE_TO_TRAY) ?: default.closeToTray,
        )
    }

    private fun field(text: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*(\"[^\"]*\"|null|true|false|-?\\d+)").find(text)
            ?.groupValues?.getOrNull(1)

    fun update(block: (SettingsData) -> SettingsData) {
        val next = block(_state.value)
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        }
    }

    fun setDefaultProfileId(id: String?) = update { it.copy(defaultProfileId = id) }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ENGINE_PATH = "enginePath"
        const val KEY_SHOW_ACCESS_LOGS = "showAccessLogs"
        const val KEY_DEFAULT_PROFILE = "defaultProfileId"
        const val KEY_AUTO_START = "autoStartOnLaunch"
        const val KEY_CLOSE_TO_TRAY = "closeToTray"
    }
}
