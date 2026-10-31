//! Tauri 应用壳：commands / events / 插件注册 / 托盘。
//!
//! 分层约定：业务逻辑一律放 `frpc-core`（可独立测试），
//! 本 crate 只做 Tauri IPC 胶水（invoke 参数校验、event 推送、插件装配）。

mod bridge;
mod commands;
mod state;

use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    Manager, WindowEvent,
};
use tauri_plugin_single_instance::init as single_instance;

use state::{AppPaths, AppState, EngineManager};

/// 桌面 / 移动统一入口（main.rs / mobile_entry_point 调用）。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(single_instance(|app, _args, _cwd| {
            // 二次启动：唤起已有窗口
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        .setup(|app| {
            // 核心层监管器：路径在应用装配时确定（与 Kotlin AppPaths 对齐）
            let paths = AppPaths::resolve();
            let cfg = frpc_core::supervisor::SupervisorConfig {
                engine_path: paths.bin_dir.join(&paths.engine_file_name),
                run_dir: paths.run_dir.clone(),
                run_config_file: paths.run_config_file(),
                ..Default::default()
            };
            let supervisor = frpc_core::supervisor::Supervisor::new(cfg);
            let mut app_state = AppState::new(supervisor);
            app_state.resource_dir = app.path().resource_dir().ok();
            app.manage(app_state);

            // 事件桥：核心层 → 前端
            let handle = app.handle().clone();
            bridge::start(handle, &app.state::<AppState>());

            // 托盘 + 关闭行为
            setup_tray(app)?;

            // 设置了「启动即连」时自动启动默认档案
            {
                let state = app.state::<AppState>();
                let settings = state.settings();
                if settings.auto_start_on_launch {
                    if let Some(default_id) = settings.default_profile_id.clone() {
                        let mgr = EngineManager { state: &state };
                        if let Ok(engine) = mgr.ensure_ready() {
                            *state.engine_path.write().unwrap() = engine;
                            if let Some(profile) = state.find_profile(&default_id) {
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

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_profiles,
            commands::get_profile,
            commands::save_profile,
            commands::delete_profile,
            commands::duplicate_profile,
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
            commands::get_logs,
            commands::clear_logs,
            commands::export_logs,
            commands::get_settings,
            commands::set_settings,
            commands::get_data_dir,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn setup_tray(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    let show = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main-tray")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .tooltip("Frpc-Android-Multi");
    if let Some(icon) = app.default_window_icon().cloned() {
        tray = tray.icon(icon);
    }
    tray.on_menu_event(|app, event| match event.id.as_ref() {
        "show" => {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }
        "quit" => {
            let supervisor = &app.state::<AppState>().supervisor;
            tauri::async_runtime::block_on(supervisor.stop_blocking());
            app.exit(0);
        }
        _ => {}
    })
    .on_tray_icon_event(|tray, event| {
        if let tauri::tray::TrayIconEvent::Click { .. } = event {
            let app = tray.app_handle();
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }
    })
    .build(app)?;

    // 关闭到托盘（settings.closeToTray，默认 true）
    let app_handle = app.handle().clone();
    if let Some(window) = app.get_webview_window("main") {
        window.on_window_event(move |event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let close_to_tray = app_handle.state::<AppState>().settings().close_to_tray;
                if close_to_tray {
                    api.prevent_close();
                    if let Some(w) = app_handle.get_webview_window("main") {
                        let _ = w.hide();
                    }
                } else {
                    let supervisor = &app_handle.state::<AppState>().supervisor;
                    tauri::async_runtime::block_on(supervisor.stop_blocking());
                    app_handle.exit(0);
                }
            }
        });
    }
    Ok(())
}
