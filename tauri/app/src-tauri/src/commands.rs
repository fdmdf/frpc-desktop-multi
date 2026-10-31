//! Tauri commands：档案 / 引擎 / 日志 / 设置 / 校验（IPC 面，见迁移方案第 5 节）。

use std::path::PathBuf;

use frpc_core::logs::LogLine;
use frpc_core::model::FrpcConfig;
use frpc_core::store::{ImportSummary, ServerProfile, SettingsData};
use frpc_core::supervisor::{ProfileRef, RunState};
use frpc_core::toml::FrpcToml;
use frpc_core::validate::ValidationIssue;

use crate::state::{validate_config_live, AppState, EngineManager};

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
    let issues = validate_config_live(&state, &profile.config);
    if frpc_core::validate::ConfigValidator::has_error(&issues) {
        return Err(issues);
    }
    Ok(state.upsert_profile(profile))
}

#[tauri::command]
pub fn delete_profile(id: String, state: tauri::State<'_, AppState>) {
    state.delete_profile(&id);
    // 联动清理：删掉的可能正是「待重载」或「默认」那份，否则界面会一直挂着旧状态
    if state.supervisor.pending_reload().borrow().as_deref() == Some(id.as_str()) {
        state.supervisor.clear_pending_reload();
    }
    let mut settings = state.settings();
    if settings.default_profile_id.as_deref() == Some(id.as_str()) {
        settings.default_profile_id = None;
        state.save_settings(settings);
    }
}

#[tauri::command]
pub fn duplicate_profile(
    id: String,
    state: tauri::State<'_, AppState>,
) -> Result<ServerProfile, String> {
    state
        .duplicate_profile(&id)
        .ok_or_else(|| "档案不存在".into())
}

/// 主页 / 配置列表里的「启用」开关：只翻转这一条规则的 `enabled`，不做全量校验。
///
/// 不走 `save_profile` 是因为：① 校验里的「admin 端口被占用」对运行中的档案必然
/// 误报（那个端口正被自己的 frpc 占着），用户只是想关一条代理却收到一个看不懂的
/// 报错；② 关闭一条规则不会引入任何非法字段，没有拦的理由。运行中则登记
/// 「待重载」，让界面提示需要点「重载配置」而不是显示成保存失败。
#[tauri::command]
pub fn set_rule_enabled(
    profile_id: String,
    name: String,
    visitor: bool,
    enabled: bool,
    state: tauri::State<'_, AppState>,
) -> Result<ServerProfile, String> {
    let mut profile = state
        .find_profile(&profile_id)
        .ok_or_else(|| "档案不存在".to_string())?;
    let flag = if enabled { None } else { Some(false) };
    if visitor {
        let target = profile
            .config
            .visitors
            .iter_mut()
            .find(|v| v.name == name)
            .ok_or_else(|| format!("访问者「{name}」不存在"))?;
        target.enabled = flag;
    } else {
        let target = profile
            .config
            .proxies
            .iter_mut()
            .find(|p| p.name == name)
            .ok_or_else(|| format!("代理「{name}」不存在"))?;
        target.enabled = flag;
    }
    let saved = state.upsert_profile(profile);
    if state.supervisor.is_running() {
        state.supervisor.mark_pending_reload(&saved.id);
    }
    Ok(saved)
}

#[tauri::command]
pub fn export_profile_toml(
    id: String,
    state: tauri::State<'_, AppState>,
) -> Result<String, String> {
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
pub fn validate_config_cmd(
    config: FrpcConfig,
    state: tauri::State<'_, AppState>,
) -> Vec<ValidationIssue> {
    validate_config_live(&state, &config)
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
    // Android 没有 resources/engine/VERSION（引擎在 nativeLibraryDir），
    // 打包版本缺失时退回 `frpc -v` 探测，避免界面上一直显示 unknown。
    let mut version = mgr.bundled_version(state.resource_dir.as_deref());
    if version == "unknown" {
        let probed = mgr.probe_version();
        if !probed.is_empty() {
            version = probed;
        }
    }
    EngineInfo {
        path: engine_file.display().to_string(),
        exists: engine_file.is_file(),
        using_custom: mgr.is_using_custom(),
        bundled_version: version,
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
pub fn check_before_start(id: String, state: tauri::State<'_, AppState>) -> Option<String> {
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
    state
        .supervisor
        .visitors()
        .borrow()
        .iter()
        .cloned()
        .collect()
}

#[tauri::command]
pub fn get_status_snapshot(
    state: tauri::State<'_, AppState>,
) -> Option<frpc_core::admin::ProxyStatusSnapshot> {
    state.supervisor.status().borrow().clone()
}

#[tauri::command]
pub fn get_active_counts(
    state: tauri::State<'_, AppState>,
) -> frpc_core::supervisor::ActiveRuleCounts {
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

#[tauri::command]
pub fn mark_pending_reload(profile_id: String, state: tauri::State<'_, AppState>) {
    state.supervisor.mark_pending_reload(&profile_id);
}

#[tauri::command]
pub fn clear_pending_reload(state: tauri::State<'_, AppState>) {
    state.supervisor.clear_pending_reload();
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
    let target = state.paths.logs_dir.join(format!("frpc-logs-{ts}.txt"));
    state.supervisor.save_logs(&target).map_err(err)?;
    Ok(target.display().to_string())
}

/// 日志全文（前端配合 dialog.save 自选保存位置，对齐旧版 FileDialog.SAVE）。
#[tauri::command]
pub fn get_logs_text(state: tauri::State<'_, AppState>) -> String {
    state.supervisor.logs_text()
}

// ---------- 设置 ----------

#[tauri::command]
pub fn get_settings(state: tauri::State<'_, AppState>) -> SettingsData {
    state.settings()
}

#[tauri::command]
pub fn set_settings(settings: SettingsData, state: tauri::State<'_, AppState>) {
    // Android 只认随包内置的 libfrpc.so：自定义引擎路径既不可 exec，界面也已隐藏，
    // 落盘的遗留值一律清掉，避免旧配置把引擎指到一个不可用的路径。
    #[cfg(target_os = "android")]
    let settings = SettingsData {
        engine_path: String::new(),
        ..settings
    };
    state
        .supervisor
        .set_show_access_logs(settings.show_access_logs);
    state.save_settings(settings);
}

#[tauri::command]
pub fn get_data_dir(state: tauri::State<'_, AppState>) -> String {
    state.paths.home.display().to_string()
}

// ---------- 系统集成 ----------

/// 用系统默认浏览器打开 URL（对齐旧版 Desktop.browse）。
#[tauri::command]
pub fn open_url(url: String) -> Result<(), String> {
    open_url_inner(&url)
}

/// 用系统文件管理器打开目录（对齐旧版 Desktop.open）。
#[tauri::command]
pub fn open_path(path: String) -> Result<(), String> {
    open_path_inner(&path)
}

pub fn open_url_inner(url: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        // explorer 会用默认浏览器打开 URL 且不弹控制台窗口（cmd /c start 会闪 CMD）
        std::process::Command::new("explorer")
            .arg(url)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(url)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(all(unix, not(target_os = "macos"), not(target_os = "android")))]
    {
        std::process::Command::new("xdg-open")
            .arg(url)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(target_os = "android")]
    {
        // Android 没有 xdg-open，走 Intent.ACTION_VIEW（Kotlin 侧的 FrpcHost::openUrl）
        crate::android::open_url(url)
    }
}

/// Android：开关隧道运行期的前台保活通知。
///
/// 文案由前端传（i18n 在前端），Rust 只负责转成 JNI 调用；桌面端没有这个概念，
/// 直接返回成功，前端不需要为平台写分支。
#[tauri::command]
pub fn set_keepalive(enabled: bool, title: String, text: String) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        if enabled {
            crate::android::keepalive_start(&title, &text)
        } else {
            crate::android::keepalive_stop()
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (enabled, title, text);
        Ok(())
    }
}

/// Android：把应用加入电池优化白名单；返回 true 表示原本已在白名单。
#[tauri::command]
pub fn request_battery_exemption() -> Result<bool, String> {
    #[cfg(target_os = "android")]
    {
        crate::android::request_battery_exemption()
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(true)
    }
}

pub fn open_path_inner(path: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(path)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(path)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    // Android 属于 unix 但没有 xdg-open，也没有可唤起的文件管理器：明确报错，
    // 前端在 Android 上直接隐藏这类按钮
    #[cfg(target_os = "android")]
    {
        let _ = path;
        Err("当前平台不支持打开系统文件管理器".into())
    }
    #[cfg(all(unix, not(target_os = "macos"), not(target_os = "android")))]
    {
        std::process::Command::new("xdg-open")
            .arg(path)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}
