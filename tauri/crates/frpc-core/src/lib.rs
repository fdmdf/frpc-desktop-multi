//! # frpc-core
//!
//! frpc 桌面 / Android 客户端的核心逻辑层（Tauri 2 重写，方案见
//! `docs/tauri-migration-plan.md`）。
//!
//! 设计约束：
//! - **不依赖任何 UI 框架**（Tauri / Vue），可独立编译与测试；
//! - Android 上 `supervisor`（进程监管）能力由 Kotlin 前台服务插件承担，
//!   本层在 Android 提供能力子集（model / toml / validate / admin / logs）；
//! - 从 Kotlin 移植的每个逻辑模块，必须同步移植对应单元测试（对拍，
//!   映射表见迁移方案第 6 节）。

pub mod admin;
pub mod logs;
pub mod metrics;
pub mod model;
pub mod supervisor;
pub mod toml;
pub mod validate;

/// CI 冒烟探测：核心层可链接、可测试（M0 骨架验证用）。
pub fn core_ready() -> bool {
    true
}

#[cfg(test)]
mod tests {
    #[test]
    fn smoke_core_ready() {
        assert!(super::core_ready());
    }
}
