//! Tauri 应用壳：commands / events / 插件注册。
//!
//! 分层约定：业务逻辑一律放 `frpc-core`（可独立测试），
//! 本 crate 只做 Tauri IPC 胶水（invoke 参数校验、event 推送、插件装配）。

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
