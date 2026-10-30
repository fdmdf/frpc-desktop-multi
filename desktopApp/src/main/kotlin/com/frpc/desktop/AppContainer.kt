package com.frpc.desktop

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.data.ProfileStore
import com.frpc.desktop.data.SettingsStore
import com.frpc.desktop.engine.EngineManager
import com.frpc.desktop.engine.FrpcSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer {
    val settings = SettingsStore(AppPaths.settingsFile)
    val profiles = ProfileStore(AppPaths.profilesFile)
    val engine = EngineManager(settings)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supervisor = FrpcSupervisor(scope, engine, profiles, settings)

    fun init() {
        scope.launch { profiles.load() }
        supervisor.cleanupOrphans()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { supervisor.stopBlocking() }
            },
        )
    }

    fun shutdown() {
        runCatching { supervisor.stopBlocking() }
    }
}
