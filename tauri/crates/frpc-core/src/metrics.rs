//! 进程资源指标：CPU / 内存 / 线程 / 运行时长实时采样。
//!
//! 移植来源（M1）：
//! - `desktopApp/src/main/kotlin/com/frpc/desktop/runtime/ProcessMetrics.kt`
//!   （现实现手读 `/proc/<pid>/stat`，Windows 走另外分支）
//! - `shared/src/jvmCommonMain/kotlin/com/frpc/shared/runtime/ProcessStats.kt`
//!
//! 升级点：`sysinfo` 一套实现通吃三平台，删除平台分支代码。
//!
//! 对拍测试：`ProcessStatsTest`（shared/src/jvmCommonTest，口径对齐）。
