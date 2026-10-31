//! frpc admin API（webServer）客户端：真实代理 / 访问者状态，含远程端口。
//!
//! 移植来源（M1）：
//! - `shared/src/jvmCommonMain/kotlin/com/frpc/shared/admin/FrpcAdminClient.kt`
//! - `shared/src/commonMain/kotlin/com/frpc/shared/admin/AdminModels.kt`
//!
//! 实现要点：`reqwest` + `rustls`（避免各平台 OpenSSL 依赖）；
//! 接口关闭时回退日志推断的行为由 supervisor / logs 层配合完成。
//!
//! 对拍测试：`FrpcAdminClientTest`（shared/src/jvmCommonTest）。
