package com.frpc.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

/**
 * 界面语言（应用内切换，不跟随系统时覆盖系统语言）。
 * 取值即 Locale 的 BCP-47 标签；`zh-CN` / `en` 有对应的 values-* 资源目录。
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val ZH = "zh-CN"
    const val EN = "en"
    val ALL = listOf(SYSTEM, ZH, EN)
}

/**
 * 应用设置（SharedPreferences + StateFlow 双通道，轻量零依赖）。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("frpc_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _autoStartOnBoot = MutableStateFlow(prefs.getBoolean(KEY_AUTO_START, false))
    val autoStartOnBoot: StateFlow<Boolean> = _autoStartOnBoot.asStateFlow()

    private val _language =
        MutableStateFlow(prefs.getString(KEY_LANGUAGE, AppLanguage.SYSTEM) ?: AppLanguage.SYSTEM)
    val language: StateFlow<String> = _language.asStateFlow()

    private val _defaultProfileId = MutableStateFlow(prefs.getString(KEY_DEFAULT_PROFILE, null))
    val defaultProfileId: StateFlow<String?> = _defaultProfileId.asStateFlow()

    /**
     * 是否在日志中显示状态接口的访问记录（frpc 每 4 秒被轮询一次会刷屏）。
     * 默认关闭；排查状态接口问题时可打开。
     */
    private val _showAccessLogs = MutableStateFlow(prefs.getBoolean(KEY_SHOW_ACCESS_LOGS, false))
    val showAccessLogs: StateFlow<Boolean> = _showAccessLogs.asStateFlow()

    fun setShowAccessLogs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ACCESS_LOGS, enabled).apply()
        _showAccessLogs.value = enabled
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
        _themeMode.value = mode
    }

    fun setLanguage(tag: String) {
        val next = tag.takeIf { it in AppLanguage.ALL } ?: AppLanguage.SYSTEM
        prefs.edit().putString(KEY_LANGUAGE, next).apply()
        _language.value = next
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        _autoStartOnBoot.value = enabled
    }

    fun setDefaultProfileId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_DEFAULT_PROFILE) else putString(KEY_DEFAULT_PROFILE, id)
        }.apply()
        _defaultProfileId.value = id
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_LANGUAGE = "language"
        const val KEY_AUTO_START = "auto_start_on_boot"
        const val KEY_DEFAULT_PROFILE = "default_profile_id"
        const val KEY_SHOW_ACCESS_LOGS = "show_access_logs"
    }
}
