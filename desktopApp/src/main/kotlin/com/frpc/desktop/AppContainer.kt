package com.frpc.desktop

import com.frpc.desktop.core.AppPaths
import com.frpc.desktop.core.CleanShutdownFlag
import com.frpc.desktop.core.StartupTrace
import com.frpc.desktop.data.ProfileStore
import com.frpc.desktop.data.SettingsStore
import com.frpc.desktop.engine.EngineManager
import com.frpc.desktop.engine.FrpcSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 应用级依赖容器（桌面端单窗口，手动装配即可，不引 DI 框架） */
class AppContainer {
    val settings = SettingsStore(AppPaths.settingsFile)
    val profiles = ProfileStore(AppPaths.profilesFile)
    val engine = EngineManager(settings)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supervisor = FrpcSupervisor(scope, engine, profiles, settings)

    /** 上次是不是异常退出（标记文件是上一轮留下的）→ 决定要不要扫孤儿 frpc */
    @Volatile
    private var orphanScanNeeded = false

    /**
     * 只让第一次调用真正发起扫描。
     * 用 CAS 而不是「判断再赋值」：正常模式下「托盘装好」与「窗口首帧」两处都会调用它，
     * 而且分别跑在 EDT 与 Compose 的协程上，check-then-set 在并发下会两边都通过。
     */
    private val orphanScanDone = AtomicBoolean(false)

    fun init() {
        // 写「本轮已启动」标记；返回 true 表示上一轮没删掉它（= 上次没正常退出）
        orphanScanNeeded = CleanShutdownFlag.markStart()
        StartupTrace.mark("container.init orphanScanNeeded=$orphanScanNeeded")
        scope.launch { profiles.load() }
        // 兜底：进程被强杀（任务管理器/崩溃）时也别留下 frpc 子进程
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { supervisor.stopBlocking() }
            },
        )
    }

    /**
     * 首帧之后再调：只有上次异常退出才扫孤儿。
     *
     * 为什么不放在 `init()` 里：Windows 上这条路径要 fork `powershell` 做 CIM 查询，
     * 冷启动 1.5~4s，放在启动早期会和首屏抢 CPU / 磁盘 / 杀软扫描；
     * 而它其实**不阻塞 EDT**（跑在 `Dispatchers.IO`），延后到首帧之后用户完全无感。
     * 正常退出时这一步直接跳过（绝大多数启动都是这条路）。
     */
    fun cleanupOrphansIfNeeded() {
        // 幂等：正常模式下「托盘装好」与「窗口首帧」两处都会调，只做第一次
        if (!orphanScanDone.compareAndSet(false, true)) return
        if (!orphanScanNeeded) {
            StartupTrace.mark("cleanupOrphans skipped (last shutdown was clean)")
            return
        }
        supervisor.cleanupOrphans()
    }

    /** 退出前同步停掉引擎；不要在协程里慢慢收尾，JVM 会先退出 */
    fun shutdown() {
        runCatching { supervisor.stopBlocking() }
        // 子进程都停干净了 → 下次启动不用扫孤儿
        CleanShutdownFlag.markClean()
    }
}
