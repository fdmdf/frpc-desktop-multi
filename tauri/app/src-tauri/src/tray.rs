//! 桌面托盘：菜单随运行状态变化。
//!
//! 旧实现是**静态菜单**，「启动 / 停止（默认配置）」永远是同一句文案、始终可点，
//! 用户从托盘看不出当前是运行还是停止，只能点开窗口确认。现在：
//!
//! - 第一行是不可点的状态摘要（● 运行中 · 档案名 / ○ 已停止 / ⚠ 启动失败）；
//! - 第二行随状态变成「启动隧道」或「停止隧道」，启动中 / 停止中置灰；
//! - 「重启隧道」只在真正运行时可用；
//! - 托盘 tooltip 也带上状态，鼠标悬停即可看到。
//!
//! 状态变化由 `bridge` 里的 engine-state 监听驱动（见 `bridge::start`），
//! 每次变化重建一遍菜单 —— 菜单项只有十来个，重建比逐项 set_text 更好维护。

use frpc_core::supervisor::{Phase, RunState};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, WindowEvent};

use crate::state::AppState;

pub const TRAY_ID: &str = "main-tray";

fn is_running(phase: Phase) -> bool {
    matches!(phase, Phase::Running | Phase::Starting)
}

fn is_busy(phase: Phase) -> bool {
    matches!(phase, Phase::Starting | Phase::Stopping)
}

fn profile_label(st: &RunState) -> String {
    if st.profile_name.trim().is_empty() {
        "默认配置".to_string()
    } else {
        st.profile_name.clone()
    }
}

/// 菜单首行的状态摘要。带 pending 时说明配置已改但还没重载。
fn status_text(st: &RunState, pending: bool) -> String {
    let name = profile_label(st);
    match st.phase {
        Phase::Running if pending => format!("● 运行中 · {name}（配置待生效）"),
        Phase::Running => format!("● 运行中 · {name}"),
        Phase::Starting => format!("◐ 正在启动 · {name}"),
        Phase::Stopping => format!("◐ 正在停止 · {name}"),
        Phase::Error => "⚠ 启动失败".to_string(),
        Phase::Idle => "○ 已停止".to_string(),
    }
}

fn build_menu(app: &AppHandle, st: &RunState, pending: bool) -> tauri::Result<Menu<tauri::Wry>> {
    let running = is_running(st.phase);
    let busy = is_busy(st.phase);
    let sep = || PredefinedMenuItem::separator(app);

    let status = MenuItem::with_id(
        app,
        "tray-status",
        status_text(st, pending),
        false,
        None::<&str>,
    )?;
    let toggle = MenuItem::with_id(
        app,
        "toggle",
        if running {
            "停止隧道"
        } else {
            "启动隧道"
        },
        !busy,
        None::<&str>,
    )?;
    let restart = MenuItem::with_id(
        app,
        "restart",
        "重启隧道",
        st.phase == Phase::Running,
        None::<&str>,
    )?;
    let show = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
    let panel = MenuItem::with_id(
        app,
        "status",
        "打开 frp 面板",
        st.phase == Phase::Running && st.admin_port > 0,
        None::<&str>,
    )?;
    let logs_dir = MenuItem::with_id(app, "logsdir", "打开日志目录", true, None::<&str>)?;
    let data_dir = MenuItem::with_id(app, "datadir", "打开数据目录", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;

    Menu::with_items(
        app,
        &[
            &status,
            &sep()?,
            &toggle,
            &restart,
            &sep()?,
            &show,
            &panel,
            &logs_dir,
            &data_dir,
            &sep()?,
            &quit,
        ],
    )
}

/// 状态变化后刷新菜单与 tooltip（engine-state 监听里调用）。
pub fn refresh(app: &AppHandle, st: &RunState) {
    let Some(tray) = app.tray_by_id(TRAY_ID) else {
        return;
    };
    let pending = app
        .state::<AppState>()
        .supervisor
        .pending_reload()
        .borrow()
        .is_some();
    if let Ok(menu) = build_menu(app, st, pending) {
        let _ = tray.set_menu(Some(menu));
    }
    let _ = tray.set_tooltip(Some(format!(
        "frpc-desktop-multi — {}",
        status_text(st, pending)
    )));
}

pub fn setup(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    let handle = app.handle().clone();
    let initial = app.state::<AppState>().supervisor.state().borrow().clone();
    let menu = build_menu(&handle, &initial, false)?;
    let mut tray = TrayIconBuilder::with_id(TRAY_ID)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .tooltip(format!(
            "frpc-desktop-multi — {}",
            status_text(&initial, false)
        ));
    if let Some(icon) = app.default_window_icon().cloned() {
        tray = tray.icon(icon);
    }
    tray.on_menu_event(|app, event| handle_menu_event(app, event.id.as_ref()))
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

fn handle_menu_event(app: &AppHandle, id: &str) {
    let state = app.state::<AppState>();
    match id {
        "show" => {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }
        "toggle" => {
            let phase = state.supervisor.state().borrow().phase;
            if is_running(phase) {
                state.supervisor.stop();
            } else {
                let settings = state.settings();
                let profile = settings
                    .default_profile_id
                    .as_deref()
                    .and_then(|id| state.find_profile(id))
                    .or_else(|| state.profiles.read().ok().and_then(|g| g.first().cloned()));
                match profile {
                    Some(p) => {
                        if let Err(reason) = crate::start_profile(&state, p) {
                            crate::log_engine_error(&state, reason);
                        }
                    }
                    None => crate::log_engine_error(&state, "没有可启动的配置".into()),
                }
            }
        }
        "restart" => {
            let st = state.supervisor.state().borrow().clone();
            if let Some(pid) = st.profile_id {
                if let Some(p) = state.find_profile(&pid) {
                    let handle = app.clone();
                    tauri::async_runtime::spawn(async move {
                        let s = handle.state::<AppState>();
                        s.supervisor.stop();
                        // 等真正停下再启动：上次进程还没退出时 admin 端口仍被占用，
                        // 立刻 start 会直接进入 ERROR
                        let deadline =
                            tokio::time::Instant::now() + std::time::Duration::from_secs(8);
                        while tokio::time::Instant::now() < deadline {
                            let phase = s.supervisor.state().borrow().phase;
                            if !matches!(phase, Phase::Stopping | Phase::Starting | Phase::Running)
                            {
                                break;
                            }
                            tokio::time::sleep(std::time::Duration::from_millis(150)).await;
                        }
                        if let Err(reason) = crate::start_profile(&s, p) {
                            crate::log_engine_error(&s, reason);
                        }
                    });
                }
            }
        }
        "status" => {
            let port = state.supervisor.state().borrow().admin_port;
            if port > 0 {
                let _ = crate::commands::open_url(format!("http://127.0.0.1:{port}/static/"));
            }
        }
        "logsdir" => {
            let _ = crate::commands::open_path(state.paths.logs_dir.display().to_string());
        }
        "datadir" => {
            let _ = crate::commands::open_path(state.paths.home.display().to_string());
        }
        "quit" => {
            let supervisor = &state.supervisor;
            tauri::async_runtime::block_on(supervisor.stop_blocking());
            app.exit(0);
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn st(phase: Phase, name: &str, port: i32) -> RunState {
        RunState {
            phase,
            profile_name: name.into(),
            admin_port: port,
            ..Default::default()
        }
    }

    /// 托盘第一行必须能区分运行 / 停止 —— 这就是「托盘看不出状态」这个问题的回归测试
    #[test]
    fn status_line_distinguishes_phases() {
        let running = status_text(&st(Phase::Running, "办公室", 7400), false);
        let stopped = status_text(&st(Phase::Idle, "", 0), false);
        let failed = status_text(&st(Phase::Error, "办公室", 0), false);

        assert!(running.contains("运行中"), "{running}");
        assert!(running.contains("办公室"), "{running}");
        assert!(stopped.contains("已停止"), "{stopped}");
        assert!(failed.contains("启动失败"), "{failed}");
        assert_ne!(running, stopped);
    }

    /// 配置改过但没重载时，托盘也要能看出来
    #[test]
    fn status_line_marks_pending_reload() {
        let plain = status_text(&st(Phase::Running, "办公室", 7400), false);
        let pending = status_text(&st(Phase::Running, "办公室", 7400), true);
        assert!(pending.contains("配置待生效"), "{pending}");
        assert_ne!(plain, pending);
    }

    /// 停止状态下「停止隧道」不该可点；启动中两边的操作都要置灰
    #[test]
    fn operation_flags_follow_phase() {
        assert!(is_running(Phase::Running));
        assert!(!is_running(Phase::Idle));
        assert!(is_busy(Phase::Starting));
        assert!(is_busy(Phase::Stopping));
        assert!(!is_busy(Phase::Running));
    }

    /// 没有档案名时不要显示成空字符串
    #[test]
    fn empty_profile_name_falls_back() {
        assert!(status_text(&st(Phase::Running, "  ", 7400), false).contains("默认配置"));
    }
}
