//! 进程监管：拉起 / 停止 / 热重载 frpc 子进程，异常退出按指数退避自动重启。
//!
//! 移植来源（M1）：
//! - `desktopApp/src/main/kotlin/com/frpc/desktop/engine/EngineManager.kt`
//! - `desktopApp/src/main/kotlin/com/frpc/desktop/engine/FrpcSupervisor.kt`
//!
//! 实现要点：`tokio::process` + 状态机（IDLE / STARTING / RUNNING / BACKOFF / STOPPED），
//! 状态变化通过 Tauri event（`state-changed`）推给前端。
//!
//! Android 分叉：本模块在 Android 不直接监管进程，命令由 Kotlin 前台服务
//! 插件执行（复用 `androidApp/service/FrpcSupervisor.kt` 逻辑），M4 落地。
