package io.github.fdmdf.frpc.service

import android.content.Context
import android.content.Intent
import app.tauri.Command
import app.tauri.Plugin
import app.tauri.PluginInvoke
import app.tauri.annotation.TauriPlugin

/**
 * Tauri Android 插件入口：把既有前台服务层（androidApp/service/ 的六个类）
 * 挂到 Tauri IPC 上。Rust 侧 `#[cfg(target_os = "android")]` 的
 * start_tunnel / stop_tunnel / reload_tunnel 命令最终落到这里。
 *
 * 参考实现：需在装有 SDK/NDK 的环境随 `tauri android init` 生成的工程一起
 * 编译验证（见同目录 README.md 第 5 步）。
 */
@TauriPlugin
class FrpcTauriPlugin(private val context: Context) : Plugin(context) {

    private var runningProfileId: String? = null

    /** 启动隧道：配置 TOML 由 Rust 核心层编码后传入，服务层只负责拉起进程。 */
    @Command
    fun start(invoke: PluginInvoke) {
        val profileId = invoke.getString("profileId") ?: ""
        val tomlText = invoke.getString("toml") ?: ""
        val binary = FrpcBinaryManager(context).ensureInstalled().absolutePath
        val intent = Intent(context, FrpcService::class.java).apply {
            putExtra(FrpcService.EXTRA_PROFILE_ID, profileId)
            putExtra(FrpcService.EXTRA_TOML_TEXT, tomlText)
            putExtra(FrpcService.EXTRA_ENGINE_PATH, binary)
        }
        context.startForegroundService(intent)
        runningProfileId = profileId
        invoke.resolve()
    }

    @Command
    fun stop(invoke: PluginInvoke) {
        context.stopService(Intent(context, FrpcService::class.java))
        runningProfileId = null
        invoke.resolve()
    }

    @Command
    fun isRunning(invoke: PluginInvoke) {
        val running = FrpcService.isAlive
        invoke.resolve(
            mapOf(
                "running" to running,
                "profileId" to runningProfileId,
            )
        )
    }

    companion object {
        init {
            // 保持对 service 包的显式引用，便于审查依赖面
            @Suppress("unused")
            val _refs = arrayOf(FrpcService::class, FrpcBinaryManager::class)
        }
    }
}
