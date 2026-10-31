//! 日志：分级解析、过滤、搜索、导出；错误提示码映射。
//!
//! 移植来源（M1）：
//! - `desktopApp/src/main/kotlin/com/frpc/desktop/engine/LogParser.kt`
//! - `shared/src/commonMain/kotlin/com/frpc/shared/logs/FrpcErrorHints.kt`
//!
//! 设计：Rust 侧产出结构化日志事件（`log-line`）与错误码，
//! 文案（双语）由前端 i18n 承担 —— 对拍测试改为错误码断言
//! （原 `FrpcErrorHintsTest` 的用例语义 1:1 移植）。
