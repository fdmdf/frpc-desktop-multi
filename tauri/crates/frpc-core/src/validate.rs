//! 配置校验：必填项、端口占用、重名、类型不支持的字段、`allowPorts` 范围等。
//!
//! 移植来源（M1，逐规则对译）：
//! - `shared/src/commonMain/kotlin/com/frpc/shared/validate/ConfigValidator.kt`
//! - `shared/src/commonMain/kotlin/com/frpc/shared/ports/PortRange.kt`
//!
//! 对拍测试：
//! - `ConfigValidatorTest` / `ProxyTypeFieldTest`（commonTest）
//! - `PortRangeTest`（commonTest）
