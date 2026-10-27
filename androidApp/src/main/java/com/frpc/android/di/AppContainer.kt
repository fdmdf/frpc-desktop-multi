package com.frpc.android.di

import android.content.Context
import com.frpc.android.data.ConfigEditorStore
import com.frpc.android.data.ConfigRepository
import com.frpc.android.data.SettingsRepository
import com.frpc.android.service.FrpcBinaryManager
import java.io.File

/** 手写依赖容器：无 DI 框架，保持包体与启动开销最小 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val configRepository: ConfigRepository by lazy {
        ConfigRepository(File(appContext.filesDir, "configs.json"))
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val binaryManager: FrpcBinaryManager by lazy {
        FrpcBinaryManager(appContext)
    }

    val configEditorStore: ConfigEditorStore by lazy {
        ConfigEditorStore()
    }
}
