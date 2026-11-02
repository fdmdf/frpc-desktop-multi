//! 桌面端「开机自启动」胶水层：拼装平台参数、落盘用户意图、启动时自愈。
//!
//! 真正的跨平台逻辑在 `frpc_core::autostart`（纯逻辑 + 单测），这里只做三件事：
//! 1. 从进程环境取出当前可执行文件与家目录，构造 `AutostartSpec`；
//! 2. 开关命令：先动系统，成功后才写 `settings.autostart`，
//!    失败则不落盘 —— 磁盘上的意图必须和系统真实状态一致，否则界面会骗人；
//! 3. 启动时的自愈：程序被移动 / 升级换了安装目录后，把旧注册项改写到新路径。
//!
//! 全部函数都只返回结果、不 panic；`setup` 把自愈丢到后台线程，失败只进应用日志，
//! 自启动出任何问题都不影响应用正常启动。

use std::path::PathBuf;

use frpc_core::autostart::{
    self as core, AutostartError, AutostartSpec, AutostartStatus, Platform, RealSystem,
    ReconcileAction,
};
use frpc_core::logs::LogLevel;
use tauri::{AppHandle, Manager};

use crate::state::AppState;

/// 自启动拉起时附加的启动参数（应用据此决定「静默启动、不抢焦点」）。
pub const AUTOSTART_ARG: &str = "--autostart";

/// 应用显示名 / 注册表值名（与 productName 一致，便于用户在注册表里认出来）。
const APP_NAME: &str = "frpc-desktop-multi";
/// 反向域名标识：LaunchAgent 的 Label / 文件名，必须全局唯一。
const APP_ID: &str = "io.github.fdmdf.frpc";

fn home_dir() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

/// `$XDG_CONFIG_HOME`（必须是绝对路径才有效）→ `~/.config`
fn config_dir(home: &std::path::Path) -> PathBuf {
    std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| home.join(".config"))
}

/// 当前环境下「应该被自启动」的可执行文件。
///
/// AppImage 是个例外：`current_exe()` 指向 `/tmp/.mount_xxxx/...` 这个**每次启动都变**
/// 的挂载点，写进自启动项等于写了个一次性路径。`$APPIMAGE` 才是磁盘上真实的
/// `.AppImage` 文件，优先用它。
fn exe_path() -> Option<PathBuf> {
    std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .filter(|p| p.is_file())
        .or_else(|| std::env::current_exe().ok())
}

/// 当前环境下的自启动规格。拿不到平台或可执行文件路径时返回 None（视为不支持）。
pub fn spec() -> Option<AutostartSpec> {
    let platform = Platform::current()?;
    let exe = exe_path()?;
    let home = home_dir();
    let cfg = config_dir(&home);
    Some(AutostartSpec::new(
        platform,
        APP_NAME,
        APP_ID,
        exe,
        vec![AUTOSTART_ARG.to_string()],
        home,
        cfg,
    ))
}

/// 前端能直接展示的失败原因：`code` 用于分支（权限 / 不支持 / 其他），
/// `message` 是可以直接抄进 issue 的完整描述。
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AutostartErrorPayload {
    pub code: String,
    pub message: String,
}

impl From<AutostartError> for AutostartErrorPayload {
    fn from(e: AutostartError) -> Self {
        Self {
            code: e.code().to_string(),
            message: e.to_string(),
        }
    }
}

impl AutostartErrorPayload {
    fn unsupported(detail: impl Into<String>) -> Self {
        Self {
            code: "unsupported".into(),
            message: detail.into(),
        }
    }
}

/// 读取系统真实状态（不做任何写入）。任何异常都降级成 `supported: false` + detail。
pub fn current_status() -> AutostartStatus {
    match spec() {
        Some(spec) => core::status(&spec, &RealSystem),
        None => AutostartStatus::unsupported(
            Platform::current(),
            Some("当前平台不支持开机自启动".into()),
        ),
    }
}

/// 打开 / 关闭自启动。成功后立即持久化用户意图。
///
/// 写入后会**回读一次**再落盘：Windows 上 `reg add` 返回 0 不代表值真的存在
/// （组策略、重定向等都会让它「成功但没生效」）。回读不一致就报错，
/// 免得界面显示已开启、开机却没反应。
pub fn apply(state: &AppState, enabled: bool) -> Result<AutostartStatus, AutostartErrorPayload> {
    let spec =
        spec().ok_or_else(|| AutostartErrorPayload::unsupported("当前平台不支持开机自启动"))?;
    if enabled {
        core::enable(&spec, &RealSystem)?;
    } else {
        core::disable(&spec, &RealSystem)?;
    }

    let status = core::status(&spec, &RealSystem);
    if status.enabled != enabled && status.detail.is_none() {
        return Err(AutostartErrorPayload {
            code: "verify".into(),
            message: format!("自启动项写入后回读不一致：{}", spec.location()),
        });
    }

    // 只有系统那边成功了才落盘：否则「设置说开着、系统里没有」会持续误导用户
    let mut settings = state.settings();
    settings.autostart = enabled;
    settings.autostart_path = if enabled {
        spec.exe_path.display().to_string()
    } else {
        String::new()
    };
    state.save_settings(settings);

    Ok(status)
}

/// 启动时自愈：用户意图为「开」但注册项丢了 / 指向旧路径时修复。
///
/// 由 `setup` 在后台线程调用，失败只记日志。
pub fn reconcile_on_startup(app: &AppHandle) {
    let Some(spec) = spec() else {
        return;
    };
    let state = app.state::<AppState>();
    let settings = state.settings();
    if !settings.autostart {
        return;
    }

    let action = core::reconcile(&spec, &RealSystem, true);
    let exe_now = spec.exe_path.display().to_string();
    match action {
        ReconcileAction::Disabled | ReconcileAction::Unchanged => {
            // 顺手对齐一次记录，避免下次误判
            if settings.autostart_path != exe_now {
                let mut next = state.settings();
                next.autostart_path = exe_now;
                state.save_settings(next);
            }
        }
        ReconcileAction::Repaired { previous } => {
            let mut next = state.settings();
            next.autostart_path = exe_now.clone();
            state.save_settings(next);
            log_line(
                &state,
                LogLevel::Info,
                format!(
                    "开机自启动项已更新到新路径：{} → {exe_now}（原记录：{}）",
                    spec.location(),
                    previous.unwrap_or_else(|| "空".into())
                ),
            );
        }
        ReconcileAction::Restored => {
            let mut next = state.settings();
            next.autostart_path = exe_now.clone();
            state.save_settings(next);
            log_line(
                &state,
                LogLevel::Info,
                format!("开机自启动项缺失，已重新写入：{}", spec.location()),
            );
        }
        ReconcileAction::Failed { message } => {
            log_line(
                &state,
                LogLevel::Warn,
                format!("开机自启动项修复失败：{message}"),
            );
        }
    }
}

/// 是否由开机自启动拉起（用于静默启动）。
pub fn launched_by_autostart() -> bool {
    std::env::args().skip(1).any(|a| a == AUTOSTART_ARG)
}

fn log_line(state: &AppState, level: LogLevel, message: String) {
    use frpc_core::logs::LogLine;
    state.supervisor.append_log(LogLine {
        time_ms: frpc_core::admin::now_epoch_ms(),
        level,
        tag: "autostart".into(),
        message,
    });
}
