//! 事件桥：核心层 watch/broadcast 通道 → 前端 Tauri event。
//!
//! 事件名（前端 `listen` 对应）：
//! `engine-state` / `log-line` / `metrics-tick` / `status-refreshed` /
//! `error-hint` / `proxy-states` / `visitors-changed` / `active-counts` /
//! `server-connected` / `login-error` / `admin-reachable` / `pending-reload`

use tauri::{AppHandle, Emitter};

use crate::state::AppState;

fn spawn_watch<T, F>(
    app: AppHandle,
    mut rx: tokio::sync::watch::Receiver<T>,
    event: &'static str,
    mut transform: F,
) where
    T: Clone + Send + Sync + 'static,
    F: FnMut(&T) -> Option<serde_json::Value> + Send + 'static,
{
    tauri::async_runtime::spawn(async move {
        loop {
            if rx.changed().await.is_err() {
                return;
            }
            let value = rx.borrow_and_update().clone();
            if let Some(payload) = transform(&value) {
                let _ = app.emit(event, payload);
            }
        }
    });
}

fn spawn_broadcast<T, F>(
    app: AppHandle,
    mut rx: tokio::sync::broadcast::Receiver<T>,
    event: &'static str,
    transform: F,
) where
    T: Clone + Send + 'static,
    F: Fn(&T) -> serde_json::Value + Send + 'static,
{
    tauri::async_runtime::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(value) => {
                    let _ = app.emit(event, transform(&value));
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => return,
            }
        }
    });
}

pub fn start(app: AppHandle, state: &AppState) {
    let sup = &state.supervisor;

    spawn_watch(app.clone(), sup.state(), "engine-state", |s| {
        serde_json::to_value(s).ok()
    });
    spawn_watch(app.clone(), sup.pending_reload(), "pending-reload", |v| {
        serde_json::to_value(v).ok()
    });
    spawn_broadcast(app.clone(), sup.logs_live(), "log-line", |l| {
        serde_json::to_value(l).unwrap_or(serde_json::Value::Null)
    });
    spawn_broadcast(app.clone(), sup.hints(), "error-hint", |h| {
        serde_json::to_value(h).unwrap_or(serde_json::Value::Null)
    });
    spawn_watch(app.clone(), sup.proxy_states(), "proxy-states", |m| {
        serde_json::to_value(m).ok()
    });
    spawn_watch(app.clone(), sup.visitors(), "visitors-changed", |s| {
        serde_json::to_value(s).ok()
    });
    spawn_watch(app.clone(), sup.status(), "status-refreshed", |s| {
        serde_json::to_value(s).ok()
    });
    spawn_watch(app.clone(), sup.active_counts(), "active-counts", |c| {
        serde_json::to_value(c).ok()
    });
    spawn_watch(
        app.clone(),
        sup.server_connected(),
        "server-connected",
        |v| serde_json::to_value(v).ok(),
    );
    spawn_watch(app.clone(), sup.login_error(), "login-error", |v| {
        serde_json::to_value(v).ok()
    });
    spawn_watch(app.clone(), sup.admin_reachable(), "admin-reachable", |v| {
        serde_json::to_value(v).ok()
    });
    spawn_watch(app.clone(), sup.metrics(), "metrics-tick", |m| {
        serde_json::to_value(m).ok()
    });
}
