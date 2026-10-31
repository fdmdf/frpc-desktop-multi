//! `frpc.toml` 编解码。
//!
//! 升级点：使用 `toml_edit` 做无损往返 —— 保留注释与未建模字段；
//! 旧版 Kotlin 实现（`shared/.../toml/FrpcToml.kt`）导入时忽略未建模字段，
//! 属于 README 已知限制，本模块作为修复一并落地。
//!
//! 对拍测试（M1 移植）：
//! - `FrpcTomlTest` / `FrpcTomlAdvancedTest`（shared/src/commonTest）
//! - `FrpcTomlResourceTest`（shared/src/jvmTest）
