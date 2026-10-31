//! Tauri 应用壳：commands / events / 插件注册 / 托盘。
//!
//! 分层约定：业务逻辑一律放 `frpc-core`（可独立测试），
//! 本 crate 只做 Tauri IPC 胶水（invoke 参数校验、event 推送、插件装配）。

mod bridge;
mod commands;
mod state;

#[cfg(desktop)]
use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    WindowEvent,
};
use tauri::Manager;
#[cfg(desktop)]
use tauri_plugin_single_instance::init as single_instance;

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
            commands::mark_pending_reload,
            commands::clear_pending_reload,
            commands::get_logs,
            commands::clear_logs,
            commands::export_logs,
            commands::get_settings,
            commands::set_settings,
            commands::get_data_dir,
            commands::open_url,
            commands::open_path,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(desktop)]
fn setup_tray(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    // 菜单项与旧版自绘托盘菜单对齐
    let show = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
    let toggle = MenuItem::with_id(app, "toggle", "启动 / 停止（默认配置）", true, None::<&str>)?;
    let restart = MenuItem::with_id(app, "restart", "重启", true, None::<&str>)?;
    let status_page = MenuItem::with_id(app, "status", "打开 frp 面板", true, None::<&str>)?;
    let logs_dir = MenuItem::with_id(app, "logsdir", "打开日志目录", true, None::<&str>)?;
    let data_dir = MenuItem::with_id(app, "datadir", "打开数据目录", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &show,
            &toggle,
            &restart,
            &status_page,
            &logs_dir,
            &data_dir,
            &quit,
        ],
    )?;
    let mut tray = TrayIconBuilder::with_id("main-tray")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .tooltip("frpc-desktop-multi");
    if let Some(icon) = app.default_window_icon().cloned() {
        tray = tray.icon(icon);
    }
    tray.on_menu_event(|app, event| {
        let state = app.state::<AppState>();
        match event.id.as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            "toggle" => {
                let phase = state.supervisor.state().borrow().phase;
                if matches!(
                    phase,
                    frpc_core::supervisor::Phase::Running | frpc_core::supervisor::Phase::Starting
                ) {
                    state.supervisor.stop();
                } else {
                    let settings = state.settings();
                    let profile = settings
                        .default_profile_id
                        .as_deref()
                        .and_then(|id| state.find_profile(id))
                        .or_else(|| state.profiles.read().ok().and_then(|g| g.first().cloned()));
                    if let Some(p) = profile {
                        if let Ok(engine) = (EngineManager { state: &state }).ensure_ready() {
                            *state.engine_path.write().unwrap() = engine;
                            state.supervisor.start(frpc_core::supervisor::ProfileRef {
                                id: p.id,
                                name: p.name,
                                config: p.config,
                            });
                        }
                    }
                }
            }
            "restart" => {
                let st = state.supervisor.state().borrow().clone();
                if let Some(id) = st.profile_id {
                    if let Some(p) = state.find_profile(&id) {
                        state.supervisor.stop();
                        state.supervisor.start(frpc_core::supervisor::ProfileRef {
                            id: p.id,
                            name: p.name,
                            config: p.config,
                        });
                    }
                }
            }
            "status" => {
                let port = state.supervisor.state().borrow().admin_port;
                if port > 0 {
                    let _ = commands::open_url(format!("http://127.0.0.1:{port}/static/"));
                }
            }
            "logsdir" => {
                let _ = commands::open_path(state.paths.logs_dir.display().to_string());
            }
            "datadir" => {
                let _ = commands::open_path(state.paths.home.display().to_string());
            }
            "quit" => {
                let supervisor = &state.supervisor;
                tauri::async_runtime::block_on(supervisor.stop_blocking());
                app.exit(0);
            }
            _ => {}
        }
    })
    .on_tray_icon_event(|tray, event| {
        // 仅左键单击唤起窗口；右键保留给菜单 —— 否则抢焦点导致菜单一闪而过
        if let tauri::tray::TrayIconEvent::Click {
            button: tauri::tray::MouseButton::Left,
            button_state: tauri::tray::MouseButtonState::Up,
            ..
        } = event
        {
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
