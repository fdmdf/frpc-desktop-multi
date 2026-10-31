//! Tauri commands：档案 / 引擎 / 日志 / 设置 / 校验（IPC 面，见迁移方案第 5 节）。

use std::path::PathBuf;

use frpc_core::logs::LogLine;
use frpc_core::model::FrpcConfig;
use frpc_core::store::{ImportSummary, ServerProfile, SettingsData};
use frpc_core::supervisor::{ProfileRef, RunState};
use frpc_core::toml::FrpcToml;
use frpc_core::validate::ValidationIssue;

use crate::state::{validate_config, AppState, EngineManager};

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

// ---------- 档案 ----------

#[tauri::command]
pub fn list_profiles(state: tauri::State<'_, AppState>) -> Vec<ServerProfile> {
    state.profiles.read().map(|g| g.clone()).unwrap_or_default()
}

#[tauri::command]
pub fn get_profile(id: String, state: tauri::State<'_, AppState>) -> Result<ServerProfile, String> {
    state.find_profile(&id).ok_or_else(|| "档案不存在".into())
}

#[tauri::command]
pub fn save_profile(
    profile: ServerProfile,
    state: tauri::State<'_, AppState>,
) -> Result<ServerProfile, Vec<ValidationIssue>> {
    let issues = validate_config(&profile.config);
    if frpc_core::validate::ConfigValidator::has_error(&issues) {
        return Err(issues);
    }
    Ok(state.upsert_profile(profile))
}

#[tauri::command]
pub fn delete_profile(id: String, state: tauri::State<'_, AppState>) {
    state.delete_profile(&id);
}

#[tauri::command]
pub fn duplicate_profile(
    id: String,
    state: tauri::State<'_, AppState>,
) -> Result<ServerProfile, String> {
    state.duplicate_profile(&id).ok_or_else(|| "档案不存在".into())
}

#[tauri::command]
pub fn export_profile_toml(id: String, state: tauri::State<'_, AppState>) -> Result<String, String> {
    let profile = state.find_profile(&id).ok_or("档案不存在")?;
    Ok(FrpcToml::encode(&profile.config))
}

/// 从 TOML 文本创建档案（不落盘，前端确认后 save_profile）。
#[tauri::command]
pub fn import_toml(name: String, text: String) -> Result<ServerProfile, String> {
    crate::state::import_toml_profile(&name, &text).map_err(err)
}

#[tauri::command]
pub fn export_bundle(state: tauri::State<'_, AppState>) -> String {
    state.export_all()
}

#[tauri::command]
pub fn import_bundle(
    text: String,
    replace: bool,
    state: tauri::State<'_, AppState>,
) -> Result<ImportSummary, String> {
    state.import_all(&text, replace).map_err(err)
}

/// 保存 TOML 文本到用户指定路径。
#[tauri::command]
pub fn write_text_file(path: String, content: String) -> Result<String, String> {
    let path = PathBuf::from(path);
    std::fs::write(&path, content).map_err(err)?;
    Ok(path.display().to_string())
}

// ---------- 校验 ----------

#[tauri::command]
pub fn validate_config_cmd(config: FrpcConfig) -> Vec<ValidationIssue> {
    validate_config(&config)
}

/// 本机监听端口枚举（配置编辑器的端口参考）。
#[tauri::command]
pub fn list_listening_ports() -> Vec<frpc_core::net::ListeningPort> {
    frpc_core::net::LocalPorts::list()
}

// ---------- 引擎 ----------

#[tauri::command]
pub fn get_engine_info(state: tauri::State<'_, AppState>) -> EngineInfo {
    let mgr = EngineManager { state: &state };
    let engine_file = mgr.engine_file();
    EngineInfo {
        path: engine_file.display().to_string(),
        exists: engine_file.is_file(),
        using_custom: mgr.is_using_custom(),
        bundled_version: mgr
            .bundled_version(state.resource_dir.as_deref()),
    }
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineInfo {
    pub path: String,
    pub exists: bool,
    pub using_custom: bool,
    pub bundled_version: String,
}

// ---------- 引擎启停 ----------

#[tauri::command]
pub fn start_tunnel(id: String, state: tauri::State<'_, AppState>) -> Result<(), String> {
    let profile = state.find_profile(&id).ok_or("档案不存在")?;
    // 引擎就绪检查（自定义路径优先）
    let mgr = EngineManager { state: &state };
    let engine = mgr.ensure_ready().map_err(err)?;
    {
        let mut ep = state.engine_path.write().map_err(|_| "engine path 锁")?;
        *ep = engine.clone();
    }
    state.mark_used(&id);
    state.supervisor.start(ProfileRef {
        id: profile.id,
        name: profile.name,
        config: profile.config,
    });
    Ok(())
}

#[tauri::command]
pub fn stop_tunnel(state: tauri::State<'_, AppState>) {
    state.supervisor.stop();
}

#[tauri::command]
pub async fn reload_tunnel(state: tauri::State<'_, AppState>) -> Result<(), String> {
    let profile_id = state.supervisor.state().borrow().profile_id.clone();
    let profile = profile_id.and_then(|id| state.find_profile(&id));
    let profile_ref = profile.map(|p| ProfileRef {
        id: p.id,
        name: p.name,
        config: p.config,
    });
    state.supervisor.reload(profile_ref).await;
    Ok(())
}

#[tauri::command]
pub fn get_engine_state(state: tauri::State<'_, AppState>) -> RunState {
    state.supervisor.state().borrow().clone()
}

#[tauri::command]
pub fn check_before_start(
    id: String,
    state: tauri::State<'_, AppState>,
) -> Option<String> {
    state.find_profile(&id).map(|p| p.config).and_then(|c| {
        let mgr = EngineManager { state: &state };
        let _ = mgr.ensure_ready();
        state.supervisor.check_before_start(&c)
    })
}

// ---------- 状态查询（初始拉取；后续走事件流） ----------

#[tauri::command]
pub fn get_proxy_states(
    state: tauri::State<'_, AppState>,
) -> std::collections::HashMap<String, frpc_core::logs::LogProxyState> {
    state.supervisor.proxy_states().borrow().clone()
}

#[tauri::command]
pub fn get_visitors(state: tauri::State<'_, AppState>) -> Vec<String> {
    state.supervisor.visitors().borrow().iter().cloned().collect()
}

#[tauri::command]
pub fn get_status_snapshot(
    state: tauri::State<'_, AppState>,
) -> Option<frpc_core::admin::ProxyStatusSnapshot> {
    state.supervisor.status().borrow().clone()
}

#[tauri::command]
pub fn get_active_counts(state: tauri::State<'_, AppState>) -> frpc_core::supervisor::ActiveRuleCounts {
    *state.supervisor.active_counts().borrow()
}

#[tauri::command]
pub fn get_server_connected(state: tauri::State<'_, AppState>) -> Option<bool> {
    *state.supervisor.server_connected().borrow()
}

#[tauri::command]
pub fn get_login_error(state: tauri::State<'_, AppState>) -> Option<String> {
    state.supervisor.login_error().borrow().clone()
}

#[tauri::command]
pub fn get_admin_reachable(state: tauri::State<'_, AppState>) -> bool {
    *state.supervisor.admin_reachable().borrow()
}

#[tauri::command]
pub fn get_metrics(state: tauri::State<'_, AppState>) -> Option<frpc_core::metrics::ProcMetrics> {
    state.supervisor.metrics().borrow().clone()
}

#[tauri::command]
pub fn get_pending_reload(state: tauri::State<'_, AppState>) -> Option<String> {
    state.supervisor.pending_reload().borrow().clone()
}

// ---------- 日志 ----------

#[tauri::command]
pub fn get_logs(state: tauri::State<'_, AppState>) -> Vec<LogLine> {
    state.supervisor.log_snapshot()
}

#[tauri::command]
pub fn clear_logs(state: tauri::State<'_, AppState>) {
    state.supervisor.clear_logs();
}

/// 导出日志到应用日志目录（返回文件路径；文案/分享由前端处理）。
#[tauri::command]
pub fn export_logs(state: tauri::State<'_, AppState>) -> Result<String, String> {
    let ts = chrono::Local::now().format("%Y%m%d-%H%M%S");
    let target = state
        .paths
        .logs_dir
        .join(format!("frpc-logs-{ts}.txt"));
    state.supervisor.save_logs(&target).map_err(err)?;
    Ok(target.display().to_string())
}

// ---------- 设置 ----------

#[tauri::command]
pub fn get_settings(state: tauri::State<'_, AppState>) -> SettingsData {
    state.settings()
}

#[tauri::command]
pub fn set_settings(settings: SettingsData, state: tauri::State<'_, AppState>) {
    state.supervisor.set_show_access_logs(settings.show_access_logs);
    state.save_settings(settings);
}

#[tauri::command]
pub fn get_data_dir(state: tauri::State<'_, AppState>) -> String {
    state.paths.home.display().to_string()
}
