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

/** 应用级依赖容器（桌面端单窗口，手动装配即可，不引 DI 框架） */
class AppContainer {
    val settings = SettingsStore(AppPaths.settingsFile)
    val profiles = ProfileStore(AppPaths.profilesFile)
    val engine = EngineManager(settings)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supervisor = FrpcSupervisor(scope, engine, profiles, settings)

    fun init() {
        scope.launch { profiles.load() }
        // 启动先清扫：上次被强杀时 shutdown hook 没跑，frpc 孤儿还占着端口
        supervisor.cleanupOrphans()
        // 兜底：进程被强杀（任务管理器/崩溃）时也别留下 frpc 子进程
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { supervisor.stopBlocking() }
            },
        )
    }

    /** 退出前同步停掉引擎；不要在协程里慢慢收尾，JVM 会先退出 */
    fun shutdown() {
        runCatching { supervisor.stopBlocking() }
    }
}
