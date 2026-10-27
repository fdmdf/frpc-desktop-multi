package com.frpc.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SettingsData(
    /** system / light / dark */
    val theme: String = "system",
    /** 自定义引擎路径，空则用内置引擎 */
    val enginePath: String = "",
    /** 日志里是否显示状态接口轮询噪音 */
    val showAccessLogs: Boolean = false,
    /** 默认配置 id */
    val defaultProfileId: String? = null,
    /** 桌面端：启动时自动启动默认配置 */
    val autoStartOnLaunch: Boolean = false,
    /** 关闭窗口时最小化到托盘而不是退出 */
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
