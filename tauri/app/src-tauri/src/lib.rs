//! Tauri 应用壳：commands / events / 插件注册 / 托盘。
//!
//! 分层约定：业务逻辑一律放 `frpc-core`（可独立测试），
//! 本 crate 只做 Tauri IPC 胶水（invoke 参数校验、event 推送、插件装配）。

#[cfg(target_os = "android")]
mod android;
mod bridge;
mod commands;
mod state;
#[cfg(desktop)]
mod tray;

use tauri::Manager;
#[cfg(desktop)]
use tauri_plugin_single_instance::init as single_instance;

#[cfg(desktop)]
use frpc_core::store::ServerProfile;
use state::{AppPaths, AppState, EngineManager};

/// 桌面 / 移动统一入口（main.rs / mobile_entry_point 调用）。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default();
    // 单实例是桌面特性（Android 由系统任务栈天然保证单实例）
    #[cfg(desktop)]
    let builder = builder.plugin(single_instance(|app, _args, _cwd| {
        // 二次启动：唤起已有窗口
        if let Some(w) = app.get_webview_window("main") {
            let _ = w.show();
            let _ = w.set_focus();
        }
    }));
    builder
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // 核心层监管器：路径在应用装配时确定（与 Kotlin AppPaths 对齐）。
            // Android：数据目录用 app files 目录，引擎用 jniLibs 的 libfrpc.so。
            #[cfg(target_os = "android")]
            let paths = {
                let data_dir = app.path().app_data_dir().expect("app data dir");
                let native_lib = android_native_lib_dir();
                AppPaths::android(data_dir, native_lib)
            };
            #[cfg(not(target_os = "android"))]
            let paths = AppPaths::resolve();

            let engine_path = paths
                .engine_dir
                .as_ref()
                .map(|d| d.join(&paths.engine_file_name))
                .unwrap_or_else(|| paths.bin_dir.join(&paths.engine_file_name));
            let cfg = frpc_core::supervisor::SupervisorConfig {
                engine_path,
                run_dir: paths.run_dir.clone(),
                run_config_file: paths.run_config_file(),
                ..Default::default()
            };
            let supervisor = frpc_core::supervisor::Supervisor::new(cfg);
            let mut app_state = AppState::new_with_paths(paths, supervisor);
            app_state.resource_dir = app.path().resource_dir().ok();
            app.manage(app_state);

            // 事件桥：核心层 → 前端
            let handle = app.handle().clone();
            bridge::start(handle, &app.state::<AppState>());

            // 托盘 + 关闭行为（桌面专属；Android 无托盘概念）
            #[cfg(desktop)]
            tray::setup(app)?;

            // 设置了「启动即连」时自动启动默认档案
            {
                let state = app.state::<AppState>();
                let settings = state.settings();
                if settings.auto_start_on_launch {
                    if let Some(default_id) = settings.default_profile_id.clone() {
                        if let Some(profile) = state.find_profile(&default_id) {
                            #[cfg(desktop)]
                            if let Err(reason) = start_profile(&state, profile) {
                                log_engine_error(&state, reason);
                            }
                            #[cfg(not(desktop))]
                            {
                                let mgr = EngineManager { state: &state };
                                if let Ok(engine) = mgr.ensure_ready() {
                                    *state.engine_path.write().unwrap() = engine;
                                    state.supervisor.start(frpc_core::supervisor::ProfileRef {
                                        id: profile.id,
                                        name: profile.name,
                                        config: profile.config,
                                    });
                                }
                            }
                        }
                    }
                }
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_profiles,
            commands::get_profile,
            commands::save_profile,
            commands::delete_profile,
            commands::duplicate_profile,
            commands::set_rule_enabled,
            commands::export_profile_toml,
            commands::import_toml,
            commands::export_bundle,
            commands::import_bundle,
            commands::write_text_file,
            commands::validate_config_cmd,
            commands::list_listening_ports,
            commands::get_engine_info,
            commands::start_tunnel,
            commands::stop_tunnel,
            commands::reload_tunnel,
            commands::get_engine_state,
            commands::check_before_start,
            commands::get_proxy_states,
            commands::get_visitors,
            commands::get_status_snapshot,
            commands::get_active_counts,
            commands::get_server_connected,
            commands::get_login_error,
            commands::get_admin_reachable,
            commands::get_metrics,
            commands::get_pending_reload,
            commands::mark_pending_reload,
            commands::clear_pending_reload,
            commands::get_logs,
            commands::clear_logs,
            commands::export_logs,
            commands::get_logs_text,
            commands::get_settings,
            commands::set_settings,
            commands::get_data_dir,
            commands::open_url,
            commands::open_path,
            commands::set_keepalive,
            commands::request_battery_exemption,
            commands::battery_exemption_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

/// 启动一份档案（桌面托盘 / 开机自动启动共用）：先做启动前校验，再确保引擎就绪。
#[cfg(desktop)]
pub(crate) fn start_profile(
    state: &tauri::State<'_, AppState>,
    profile: ServerProfile,
) -> Result<(), String> {
    use frpc_core::supervisor::ProfileRef;
    if let Some(reason) = state.supervisor.check_before_start(&profile.config) {
        return Err(reason);
    }
    let engine = (EngineManager { state })
        .ensure_ready()
        .map_err(|e| e.to_string())?;
    *state
        .engine_path
        .write()
        .map_err(|_| "engine path 锁".to_string())? = engine;
    state.mark_used(&profile.id);
    state.supervisor.start(ProfileRef {
        id: profile.id,
        name: profile.name,
        config: profile.config,
    });
    Ok(())
}

/// 托盘/自启场景下启动失败：写一条应用日志，避免用户完全看不到原因。
pub(crate) fn log_engine_error(state: &tauri::State<'_, AppState>, reason: String) {
    use frpc_core::admin::now_epoch_ms;
    use frpc_core::logs::{LogLevel, LogLine};
    state.supervisor.append_log(LogLine {
        time_ms: now_epoch_ms(),
        level: LogLevel::Error,
        tag: "app".into(),
        message: format!("启动失败：{reason}"),
    });
}

/// Android：通过 JNI 从 ApplicationInfo 取 `nativeLibraryDir`
/// （APK 里以 jniLibs 形式打包的 `libfrpc.so` 会落在该目录，且该目录可执行）。
/// 初始化时序兜底：ndk-context 未就绪时返回 None（UI 仍可用，仅引擎不可启动）。
#[cfg(target_os = "android")]
fn android_native_lib_dir() -> std::path::PathBuf {
    use jni::objects::JObject;

    let attempt = std::panic::catch_unwind(|| -> Option<String> {
        let ctx = ndk_context::android_context();
        let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }.ok()?;
        let mut env = vm.attach_current_thread().ok()?;
        let context = unsafe { JObject::from_raw(ctx.context().cast()) };
        let app_info = env
            .call_method(
                &context,
                "getApplicationInfo",
                "()Landroid/content/pm/ApplicationInfo;",
                &[],
            )
            .ok()?
            .l()
            .ok()?;
        let obj = env
            .get_field(&app_info, "nativeLibraryDir", "Ljava/lang/String;")
            .ok()?
            .l()
            .ok()?;
        let jstr = jni::objects::JString::from(obj);
        let java_str = env.get_string(&jstr).ok()?;
        Some(java_str.to_string_lossy().into_owned())
    })
    .ok()
    .flatten();

    match attempt {
        Some(dir) => {
            println!("nativeLibraryDir = {dir}");
            std::path::PathBuf::from(dir)
        }
        None => {
            println!("nativeLibraryDir unavailable (ndk-context not ready)");
            std::path::PathBuf::new()
        }
    }
}
