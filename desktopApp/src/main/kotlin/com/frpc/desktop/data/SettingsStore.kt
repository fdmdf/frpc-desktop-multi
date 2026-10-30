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
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsData> = _state.asStateFlow()

    private fun read(): SettingsData = runCatching {
        if (file.exists()) json.decodeFromString<SettingsData>(file.readText()) else SettingsData()
    }.getOrDefault(SettingsData())

    fun update(block: (SettingsData) -> SettingsData) {
        val next = block(_state.value)
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        }
    }

    fun setDefaultProfileId(id: String?) = update { it.copy(defaultProfileId = id) }
}
