//! 进程监管：拉起 / 停止 / 热重载 frpc 子进程，异常退出按指数退避自动重启。
//!
//! Kotlin 来源：`desktopApp/.../engine/FrpcSupervisor.kt`（状态机逐行为对译）。
//!
//! 与 Kotlin 版的差异（有意为之）：
//! - 引擎路径由应用层解析后经 [`SupervisorConfig`] 注入（解包内置资源的
//!   `EngineManager` 属 Tauri 应用层职责）；
//! - 停止使用进程硬终止（`start_kill` ≙ Windows `destroyForcibly`），
//!   Unix 上 Kotlin 的 `destroy()`（SIGTERM 优雅退出）需要 libc 依赖，
//!   见模块尾部注释；
//! - 错误提示以结构化 [`crate::logs::ErrorHint`] 经 hints 通道广播，
//!   文案由前端本地化；
//! - 子进程所有权归 watcher 任务：停止经 `Notify` 通知 watcher 自行
//!   kill + 收尸，监管器主体不持子进程句柄，避免锁竞争。

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
#[cfg(test)]
use std::time::Instant;

use chrono::TimeZone as _;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncBufReadExt;
use tokio::process::Command;
use tokio::sync::{broadcast, watch, Mutex, Notify};
use tokio::task::JoinHandle;

use crate::admin::{FrpcAdminClient, ProxyStatusSnapshot};
use crate::logs::{
    ErrorHint, FrpcLogParser, LogLevel, LogLine, LogProxyState, ProxyLogEvent, ProxyLogParser,
};
use crate::metrics::{ProcMetrics, ProcessMetricsReader};
use crate::model::FrpcConfig;
use crate::net::PortCheck;
use crate::toml::FrpcToml;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Phase {
    #[default]
    Idle,
    Starting,
    Running,
    Stopping,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct RunState {
    #[serde(default)]
    pub phase: Phase,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile_id: Option<String>,
    #[serde(default)]
    pub profile_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pid: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub started_at_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default)]
    pub admin_enabled: bool,
    #[serde(default)]
    pub admin_port: i32,
    #[serde(default)]
    pub restart_count: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ActiveRuleCounts {
    #[serde(default)]
    pub proxies: usize,
    #[serde(default)]
    pub visitors: usize,
}

/// 一次启动所需的档案引用（应用层从 ProfileStore 取出后传入）。
#[derive(Debug, Clone)]
pub struct ProfileRef {
    pub id: String,
    pub name: String,
    pub config: FrpcConfig,
}

/// Windows：隐藏子进程控制台窗口（CREATE_NO_WINDOW，0x08000000）。
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, Clone)]
pub struct SupervisorConfig {
    pub engine_path: PathBuf,
    pub run_dir: PathBuf,
    pub run_config_file: PathBuf,
    pub status_interval: Duration,
    pub metrics_interval: Duration,
    pub max_log_lines: usize,
    pub max_restarts: u32,
    pub backoff_base_ms: u64,
    pub backoff_cap_ms: u64,
}

impl Default for SupervisorConfig {
    fn default() -> Self {
        Self {
            engine_path: PathBuf::from("frpc"),
            run_dir: PathBuf::from("."),
            run_config_file: PathBuf::from("frpc-run.toml"),
            status_interval: Duration::from_millis(4_000),
            metrics_interval: Duration::from_millis(2_000),
            max_log_lines: 3000,
            max_restarts: 5,
            backoff_base_ms: 2_000,
            backoff_cap_ms: 60_000,
        }
    }
}

#[derive(Default)]
struct Inner {
    admin: Option<FrpcAdminClient>,
    pollers: Vec<JoinHandle<()>>,
    watcher: Option<JoinHandle<()>>,
    stop_sig: Option<Arc<Notify>>,
}

/// frpc 子进程监管器（每个应用一个实例）。
///
/// 自带独立 tokio 运行时：宿主（Tauri 主线程 / 命令线程 / 测试）无需处于
/// 任何 async 上下文即可调用 `start` / `stop` —— 否则在 Tauri 命令线程里
/// `tokio::spawn` 会 panic（表现为点击"启动"后整个应用闪退）。
pub struct Supervisor {
    cfg: SupervisorConfig,
    rt: Arc<tokio::runtime::Runtime>,
    inner: Arc<Mutex<Inner>>,
    stopping: Arc<AtomicBool>,
    metrics_reader: Arc<std::sync::Mutex<ProcessMetricsReader>>,
    state_tx: watch::Sender<RunState>,
    pending_reload_tx: watch::Sender<Option<String>>,
    logs_tx: broadcast::Sender<LogLine>,
    hints_tx: broadcast::Sender<ErrorHint>,
    log_buffer: Arc<Mutex<VecDeque<LogLine>>>,
    proxy_states_tx: watch::Sender<HashMap<String, LogProxyState>>,
    visitors_tx: watch::Sender<BTreeSet<String>>,
    status_tx: watch::Sender<Option<ProxyStatusSnapshot>>,
    active_counts_tx: watch::Sender<ActiveRuleCounts>,
    server_connected_tx: watch::Sender<Option<bool>>,
    login_error_tx: watch::Sender<Option<String>>,
    admin_reachable_tx: watch::Sender<bool>,
    metrics_tx: watch::Sender<Option<ProcMetrics>>,
    show_access_logs: Arc<AtomicBool>,
}

impl Clone for Supervisor {
    fn clone(&self) -> Self {
        Self {
            cfg: self.cfg.clone(),
            rt: self.rt.clone(),
            inner: self.inner.clone(),
            stopping: self.stopping.clone(),
            metrics_reader: self.metrics_reader.clone(),
            state_tx: self.state_tx.clone(),
            pending_reload_tx: self.pending_reload_tx.clone(),
            logs_tx: self.logs_tx.clone(),
            hints_tx: self.hints_tx.clone(),
            log_buffer: self.log_buffer.clone(),
            proxy_states_tx: self.proxy_states_tx.clone(),
            visitors_tx: self.visitors_tx.clone(),
            status_tx: self.status_tx.clone(),
            active_counts_tx: self.active_counts_tx.clone(),
            server_connected_tx: self.server_connected_tx.clone(),
            login_error_tx: self.login_error_tx.clone(),
            admin_reachable_tx: self.admin_reachable_tx.clone(),
            metrics_tx: self.metrics_tx.clone(),
            show_access_logs: self.show_access_logs.clone(),
        }
    }
}

impl Supervisor {
    pub fn new(cfg: SupervisorConfig) -> Self {
        let (state_tx, _) = watch::channel(RunState::default());
        let (pending_reload_tx, _) = watch::channel(None);
        let (logs_tx, _) = broadcast::channel(1024);
        let (hints_tx, _) = broadcast::channel(64);
        let (proxy_states_tx, _) = watch::channel(HashMap::new());
        let (visitors_tx, _) = watch::channel(BTreeSet::new());
        let (status_tx, _) = watch::channel(None);
        let (active_counts_tx, _) = watch::channel(ActiveRuleCounts::default());
        let (server_connected_tx, _) = watch::channel(None);
        let (login_error_tx, _) = watch::channel(None);
        let (admin_reachable_tx, _) = watch::channel(false);
        let (metrics_tx, _) = watch::channel(None);
        Self {
            rt: shared_runtime(),
            inner: Arc::new(Mutex::new(Inner::default())),
            stopping: Arc::new(AtomicBool::new(false)),
            metrics_reader: Arc::new(std::sync::Mutex::new(ProcessMetricsReader::new())),
            cfg,
            state_tx,
            pending_reload_tx,
            logs_tx,
            hints_tx,
            log_buffer: Arc::new(Mutex::new(VecDeque::new())),
            proxy_states_tx,
            visitors_tx,
            status_tx,
            active_counts_tx,
            server_connected_tx,
            login_error_tx,
            admin_reachable_tx,
            metrics_tx,
            show_access_logs: Arc::new(AtomicBool::new(false)),
        }
    }

    // ---------- 订阅（应用层桥接到 Tauri event） ----------

    pub fn state(&self) -> watch::Receiver<RunState> {
        self.state_tx.subscribe()
    }
    pub fn pending_reload(&self) -> watch::Receiver<Option<String>> {
        self.pending_reload_tx.subscribe()
    }
    pub fn logs_live(&self) -> broadcast::Receiver<LogLine> {
        self.logs_tx.subscribe()
    }
    pub fn hints(&self) -> broadcast::Receiver<ErrorHint> {
        self.hints_tx.subscribe()
    }
    pub fn proxy_states(&self) -> watch::Receiver<HashMap<String, LogProxyState>> {
        self.proxy_states_tx.subscribe()
    }
    pub fn visitors(&self) -> watch::Receiver<BTreeSet<String>> {
        self.visitors_tx.subscribe()
    }
    pub fn status(&self) -> watch::Receiver<Option<ProxyStatusSnapshot>> {
        self.status_tx.subscribe()
    }
    pub fn active_counts(&self) -> watch::Receiver<ActiveRuleCounts> {
        self.active_counts_tx.subscribe()
    }
    pub fn server_connected(&self) -> watch::Receiver<Option<bool>> {
        self.server_connected_tx.subscribe()
    }
    pub fn login_error(&self) -> watch::Receiver<Option<String>> {
        self.login_error_tx.subscribe()
    }
    pub fn admin_reachable(&self) -> watch::Receiver<bool> {
        self.admin_reachable_tx.subscribe()
    }
    pub fn metrics(&self) -> watch::Receiver<Option<ProcMetrics>> {
        self.metrics_tx.subscribe()
    }
    pub fn log_snapshot(&self) -> Vec<LogLine> {
        self.log_buffer
            .try_lock()
            .map(|b| b.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn set_show_access_logs(&self, on: bool) {
        self.show_access_logs.store(on, Ordering::Relaxed);
    }

    pub fn mark_pending_reload(&self, profile_id: &str) {
        let _ = self.pending_reload_tx.send(Some(profile_id.to_string()));
    }

    pub fn clear_pending_reload(&self) {
        let _ = self.pending_reload_tx.send(None);
    }

    pub fn is_running(&self) -> bool {
        matches!(
            self.state_tx.borrow().phase,
            Phase::Running | Phase::Starting
        )
    }

    // ---------- 操作 ----------

    /// 启动（若已有运行中的进程则先停止，与 Kotlin `start` 一致）。
    pub fn start(&self, profile: ProfileRef) {
        let this = self.clone();
        self.rt.spawn(async move {
            let mut inner = this.inner.lock().await;
            if matches!(
                this.state_tx.borrow().phase,
                Phase::Running | Phase::Starting
            ) {
                this.stop_internal(&mut inner, Duration::from_secs(6)).await;
            }
            this.start_internal(&mut inner, profile, false).await;
        });
    }

    pub fn stop(&self) {
        let this = self.clone();
        self.rt.spawn(async move {
            let mut inner = this.inner.lock().await;
            this.stop_internal(&mut inner, Duration::from_secs(6)).await;
        });
    }

    /// 应用退出时的停止（Kotlin `stopBlocking`：3s 宽限）。
    pub async fn stop_blocking(&self) {
        let mut inner = self.inner.lock().await;
        self.stop_internal(&mut inner, Duration::from_secs(3)).await;
    }

    /// 热重载；`profile` 为 None（档案已被删除等）时不动作。
    pub async fn reload(&self, profile: Option<ProfileRef>) {
        let mut inner = self.inner.lock().await;
        let Some(profile) = profile else {
            return;
        };
        let st = self.state_tx.borrow().clone();
        let port_changed = profile
            .config
            .web_server
            .as_ref()
            .map(|w| w.port)
            .unwrap_or(0)
            != st.admin_port;
        if st.phase == Phase::Running && inner.admin.is_some() && !port_changed {
            let write_ok = self.write_toml(&profile.config).is_ok();
            let reload_ok = write_ok
                && inner
                    .admin
                    .as_ref()
                    .expect("checked above")
                    .reload()
                    .await
                    .is_ok();
            self.append_system_log(
                if reload_ok {
                    LogLevel::Info
                } else {
                    LogLevel::Error
                },
                if reload_ok {
                    "已通过 admin API 重载配置"
                } else {
                    "admin API 重载失败"
                },
            );
            if reload_ok {
                self.remember_active_counts(&profile);
                self.clear_pending_reload();
            }
        } else {
            self.stop_internal(&mut inner, Duration::from_secs(6)).await;
            self.start_internal(&mut inner, profile, false).await;
            self.clear_pending_reload();
        }
    }

    pub fn clear_logs(&self) {
        if let Ok(mut buf) = self.log_buffer.try_lock() {
            buf.clear();
        }
    }

    /// 导出日志（Kotlin `saveLogs`，格式 `HH:mm:ss.SSS [L] message`）。
    pub fn save_logs(&self, target: &std::path::Path) -> std::io::Result<std::path::PathBuf> {
        std::fs::write(target, self.logs_text())?;
        Ok(target.to_path_buf())
    }

    /// 日志全文（与 save_logs 同格式；供前端配合文件对话框自选保存位置）。
    pub fn logs_text(&self) -> String {
        self.log_snapshot()
            .into_iter()
            .map(|l| {
                let level = match l.level {
                    LogLevel::Trace => "T",
                    LogLevel::Debug => "D",
                    LogLevel::Info => "I",
                    LogLevel::Warn => "W",
                    LogLevel::Error => "E",
                };
                format!("{} [{level}] {}", format_time(l.time_ms), l.message)
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// 清理上次残留的 frpc 进程（命令行包含本应用 run_dir 的）。
    pub fn cleanup_orphans(&self) {
        let run_dir = self.cfg.run_dir.clone();
        let this = self.clone();
        self.rt.spawn(async move {
            let killed = tokio::task::spawn_blocking(move || kill_orphans(&run_dir))
                .await
                .unwrap_or(0);
            if killed > 0 {
                this.append_system_log(
                    LogLevel::Info,
                    &format!("已清理上次残留的 {killed} 个 frpc 进程"),
                );
            }
        });
    }

    /// 启动前检查；返回 Some(原因) 表示不可启动（文案与 Kotlin 一致）。
    pub fn check_before_start(&self, config: &FrpcConfig) -> Option<String> {
        if config.server_addr.trim().is_empty() {
            return Some("还没填服务器地址：先在「配置」里补上 frps 地址再启动".into());
        }
        if let Some(ws) = &config.web_server {
            if ws.port > 0 && PortCheck::is_in_use(ws.port) {
                return Some(format!(
                    "状态接口端口 {} 已被占用：同机跑多份配置时，每份要用不同的 webServer 端口",
                    ws.port
                ));
            }
        }
        None
    }

    // ---------- 内部实现 ----------

    fn remember_active_counts(&self, profile: &ProfileRef) {
        let counts = ActiveRuleCounts {
            proxies: profile
                .config
                .proxies
                .iter()
                .filter(|p| p.enabled != Some(false))
                .count(),
            visitors: profile
                .config
                .visitors
                .iter()
                .filter(|v| v.enabled != Some(false))
                .count(),
        };
        let _ = self.active_counts_tx.send(counts);
    }

    fn write_toml(&self, config: &FrpcConfig) -> std::io::Result<PathBuf> {
        if let Some(parent) = self.cfg.run_config_file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&self.cfg.run_config_file, FrpcToml::encode(config))?;
        Ok(self.cfg.run_config_file.clone())
    }

    async fn start_internal(&self, inner: &mut Inner, profile: ProfileRef, is_restart: bool) {
        self.stopping.store(false, Ordering::Relaxed);
        self.mark_state(|s| {
            s.phase = Phase::Starting;
            s.error = None;
            s.profile_id = Some(profile.id.clone());
            s.profile_name = profile.name.clone();
            s.pid = None;
            s.started_at_ms = None;
            s.restart_count = if is_restart { s.restart_count } else { 0 };
        });
        let _ = self.proxy_states_tx.send(HashMap::new());
        let _ = self.visitors_tx.send(BTreeSet::new());
        let _ = self.server_connected_tx.send(None);
        let _ = self.login_error_tx.send(None);

        if !self.cfg.engine_path.is_file() {
            let msg = format!("引擎不可用：{}", self.cfg.engine_path.display());
            self.append_system_log(LogLevel::Error, &msg);
            self.mark_state(|s| {
                s.phase = Phase::Error;
                s.error = Some(msg);
            });
            return;
        }
        if let Some(msg) = self.check_before_start(&profile.config) {
            self.append_system_log(LogLevel::Error, &msg);
            self.mark_state(|s| {
                s.phase = Phase::Error;
                s.error = Some(msg);
            });
            return;
        }

        let Ok(toml_path) = self.write_toml(&profile.config) else {
            let msg = "无法写入运行配置文件".to_string();
            self.append_system_log(LogLevel::Error, &msg);
            self.mark_state(|s| {
                s.phase = Phase::Error;
                s.error = Some(msg);
            });
            return;
        };
        self.remember_active_counts(&profile);
        self.append_system_log(
            LogLevel::Info,
            &format!(
                "启动引擎：{} -c {}",
                self.cfg.engine_path.display(),
                toml_path.display()
            ),
        );

        let mut command = Command::new(&self.cfg.engine_path);
        #[cfg(windows)]
        command.creation_flags(CREATE_NO_WINDOW);
        command
            .arg("-c")
            .arg(&toml_path)
            .current_dir(&self.cfg.run_dir)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        // 剥掉可能干扰 frpc 连网的代理环境变量（Kotlin PROXY_ENV_KEYS）
        for (k, _) in std::env::vars_os() {
            if let Some(s) = k.to_str() {
                if matches!(
                    s.to_ascii_lowercase().as_str(),
                    "http_proxy" | "https_proxy" | "all_proxy" | "no_proxy"
                ) {
                    command.env_remove(s);
                }
            }
        }
        let mut child = match command.spawn() {
            Ok(c) => c,
            Err(e) => {
                let msg = format!("启动失败：{e}");
                self.append_system_log(LogLevel::Error, &msg);
                self.mark_state(|s| {
                    s.phase = Phase::Error;
                    s.error = Some(msg);
                });
                return;
            }
        };
        let pid = child.id().map(|i| i as i64);

        let web_server = profile.config.web_server.clone();
        let admin_enabled = web_server.as_ref().map(|w| w.port > 0).unwrap_or(false);
        inner.admin = web_server
            .as_ref()
            .map(|w| FrpcAdminClient::new(&w.addr, w.port, Some(&w.user), Some(&w.password)));

        // stdout / stderr 各起一个读取任务（Kotlin redirectErrorStream 合并读取）
        if let Some(stdout) = child.stdout.take() {
            let this = self.clone();
            self.rt.spawn(async move {
                let mut lines = tokio::io::BufReader::new(stdout).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    this.ingest_line(&line);
                }
            });
        }
        if let Some(stderr) = child.stderr.take() {
            let this = self.clone();
            self.rt.spawn(async move {
                let mut lines = tokio::io::BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    this.ingest_line(&line);
                }
            });
        }

        self.mark_state(|s| {
            s.phase = Phase::Running;
            s.pid = pid;
            s.started_at_ms = Some(crate::admin::now_epoch_ms());
            s.admin_enabled = admin_enabled;
            s.admin_port = web_server.as_ref().map(|w| w.port).unwrap_or(0);
        });

        self.start_pollers(inner);
        self.watch(inner, child, profile);
    }

    fn ingest_line(&self, raw: &str) {
        if let Some(hint) = crate::logs::FrpcErrorHints::hint_for(raw) {
            let _ = self.hints_tx.send(hint);
        }
        let line = FrpcLogParser::parse(raw, crate::admin::now_epoch_ms());
        self.append_log(line);
    }

    fn start_pollers(&self, inner: &mut Inner) {
        self.stop_status_pollers(inner);
        let this = self.clone();
        inner.pollers.push(self.rt.spawn(async move {
            loop {
                tokio::time::sleep(this.cfg.status_interval).await;
                this.poll_status().await;
            }
        }));
        let this = self.clone();
        inner.pollers.push(self.rt.spawn(async move {
            loop {
                tokio::time::sleep(this.cfg.metrics_interval).await;
                this.poll_metrics().await;
            }
        }));
    }

    fn stop_status_pollers(&self, inner: &mut Inner) {
        for h in inner.pollers.drain(..) {
            h.abort();
        }
    }

    async fn poll_status(&self) {
        let st = self.state_tx.borrow().clone();
        if !st.admin_enabled || st.phase != Phase::Running {
            return;
        }
        let client = self.inner.lock().await.admin.clone();
        let Some(client) = client else {
            return;
        };
        match client.status().await {
            Ok(snap) => {
                let _ = self.status_tx.send(Some(snap));
                let _ = self.admin_reachable_tx.send(true);
            }
            Err(_) => {
                let _ = self.admin_reachable_tx.send(false);
            }
        }
    }

    async fn poll_metrics(&self) {
        let st = self.state_tx.borrow().clone();
        let Some(pid) = st.pid else {
            return;
        };
        if st.phase != Phase::Running {
            return;
        }
        // PowerShell 采样在 Windows 上可达秒级，放 blocking 线程避免卡住 stop
        let reader = self.metrics_reader.clone();
        let metrics = tokio::task::spawn_blocking(move || reader.lock().unwrap().read(pid))
            .await
            .unwrap_or(None);
        let _ = self.metrics_tx.send(metrics);
    }

    /// watcher 持有子进程所有权：正常退出 → ERROR + 自动重启；收到停止信号 → kill。
    fn watch(&self, inner: &mut Inner, mut child: tokio::process::Child, profile: ProfileRef) {
        if let Some(w) = inner.watcher.take() {
            w.abort();
        }
        let stop_sig = Arc::new(Notify::new());
        inner.stop_sig = Some(stop_sig.clone());
        let this = self.clone();
        let rt = self.rt.clone();
        let rt_inner = self.rt.clone();
        inner.watcher = Some(rt.spawn(async move {
            tokio::select! {
                st = child.wait() => {
                    let code = st.ok().and_then(|s| s.code()).unwrap_or(-1);
                    let state = this.state_tx.borrow().clone();
                    if this.stopping.load(Ordering::Relaxed)
                        || state.phase == Phase::Stopping
                        || state.phase == Phase::Idle
                    {
                        return;
                    }
                    this.append_system_log(
                        LogLevel::Error,
                        &format!("frpc 进程退出（exit={code}），准备自动重启"),
                    );
                    this.mark_state(|s| {
                        s.phase = Phase::Error;
                        s.error = Some(format!("进程退出（exit={code}）"));
                    });
                    // 自动重启放独立任务：不持有 supervisor 内锁，避免与 stop 竞争
                    let this2 = this.clone();
                    rt_inner.spawn(async move {
                        this2.auto_restart(profile).await;
                    });
                }
                _ = stop_sig.notified() => {
                    let _ = child.start_kill();
                    let _ = child.wait().await;
                }
            }
        }));
    }

    async fn auto_restart(&self, profile: ProfileRef) {
        let attempt = self.state_tx.borrow().restart_count + 1;
        if attempt > self.cfg.max_restarts {
            let msg = format!("连续 {} 次异常退出，已停止自动重启", self.cfg.max_restarts);
            self.mark_state(|s| s.error = Some(msg.clone()));
            self.append_system_log(LogLevel::Error, &msg);
            return;
        }
        let wait_ms = backoff_ms(attempt, self.cfg.backoff_base_ms, self.cfg.backoff_cap_ms);
        self.append_system_log(
            LogLevel::Warn,
            &format!("{} 秒后进行第 {attempt} 次自动重启", wait_ms / 1000),
        );
        self.mark_state(|s| s.restart_count = attempt);
        tokio::time::sleep(Duration::from_millis(wait_ms)).await;
        if self.stopping.load(Ordering::Relaxed) {
            return;
        }
        let mut inner = self.inner.lock().await;
        if self.stopping.load(Ordering::Relaxed) {
            return;
        }
        self.start_internal(&mut inner, profile, true).await;
    }

    /// 停止（调用方需已持有 inner 锁）。`watcher_wait` 为等待 watcher 收尸的上限。
    async fn stop_internal(&self, inner: &mut Inner, watcher_wait: Duration) {
        self.stopping.store(true, Ordering::Relaxed);
        let admin_port = self.state_tx.borrow().admin_port;
        let _ = self.active_counts_tx.send(ActiveRuleCounts::default());
        self.mark_state(|s| s.phase = Phase::Stopping);
        self.stop_status_pollers(inner);
        if let Some(sig) = inner.stop_sig.take() {
            sig.notify_one();
        }
        if let Some(h) = inner.watcher.take() {
            let _ = tokio::time::timeout(watcher_wait, h).await;
        }
        inner.admin = None;
        if admin_port > 0 {
            let freed = PortCheck::await_free(admin_port, 3_000).await;
            if !freed {
                self.append_system_log(
                    LogLevel::Warn,
                    &format!("端口 {admin_port} 暂时还没释放，可能有其他程序占用"),
                );
            }
        }
        self.metrics_reader.lock().unwrap().reset();
        let _ = self.metrics_tx.send(None);
        let _ = self.status_tx.send(None);
        let _ = self.admin_reachable_tx.send(false);
        let _ = self.proxy_states_tx.send(HashMap::new());
        let _ = self.visitors_tx.send(BTreeSet::new());
        let _ = self.server_connected_tx.send(None);
        let _ = self.login_error_tx.send(None);
        let (profile_id, profile_name) = {
            let st = self.state_tx.borrow();
            (st.profile_id.clone(), st.profile_name.clone())
        };
        let _ = self.state_tx.send(RunState {
            phase: Phase::Idle,
            profile_id,
            profile_name,
            ..RunState::default()
        });
        self.append_system_log(LogLevel::Info, "已停止 frpc");
    }

    fn mark_state(&self, f: impl FnOnce(&mut RunState)) {
        let mut st = self.state_tx.borrow().clone();
        f(&mut st);
        let _ = self.state_tx.send(st);
    }

    fn append_system_log(&self, level: LogLevel, message: &str) {
        self.append_log(LogLine {
            time_ms: crate::admin::now_epoch_ms(),
            level,
            tag: "app".into(),
            message: message.to_string(),
        });
    }

    fn append_log(&self, line: LogLine) {
        if !self.show_access_logs.load(Ordering::Relaxed)
            && FrpcLogParser::is_access_log(&line.message)
        {
            return;
        }
        if let Some(event) = ProxyLogParser::parse(&line.message) {
            match event {
                ProxyLogEvent::Update { name, state } => {
                    let mut map = self.proxy_states_tx.borrow().clone();
                    map.insert(name, state);
                    let _ = self.proxy_states_tx.send(map);
                }
                ProxyLogEvent::VisitorsRegistered { names } => {
                    let mut set = self.visitors_tx.borrow().clone();
                    set.extend(names);
                    let _ = self.visitors_tx.send(set);
                }
                ProxyLogEvent::LoginSuccess => {
                    let _ = self.server_connected_tx.send(Some(true));
                    let _ = self.login_error_tx.send(None);
                }
                ProxyLogEvent::LoginFailed { error } => {
                    let _ = self.server_connected_tx.send(Some(false));
                    let _ = self.login_error_tx.send(Some(error));
                }
                ProxyLogEvent::Reset => {
                    let _ = self.proxy_states_tx.send(HashMap::new());
                    let _ = self.visitors_tx.send(BTreeSet::new());
                }
            }
        }
        if let Ok(mut buf) = self.log_buffer.try_lock() {
            buf.push_back(line.clone());
            while buf.len() > self.cfg.max_log_lines {
                buf.pop_front();
            }
        }
        let _ = self.logs_tx.send(line);
    }
}

/// 进程级共享运行时：所有 Supervisor 复用；静态存储永不 drop，
/// 规避"async 上下文中 drop Runtime"的 panic，也避免测试反复建运行时。
fn shared_runtime() -> Arc<tokio::runtime::Runtime> {
    static RT: std::sync::OnceLock<Arc<tokio::runtime::Runtime>> = std::sync::OnceLock::new();
    RT.get_or_init(|| {
        Arc::new(
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
                .expect("supervisor runtime"),
        )
    })
    .clone()
}

/// 指数退避：`min(base << (attempt-1), cap)`（Kotlin `2_000L shl (attempt-1)`）。
pub fn backoff_ms(attempt: u32, base_ms: u64, cap_ms: u64) -> u64 {
    let shift = attempt.saturating_sub(1).min(31);
    (base_ms << shift).min(cap_ms)
}

fn format_time(ms: i64) -> String {
    chrono::Local
        .timestamp_millis_opt(ms)
        .single()
        .map(|t| t.format("%H:%M:%S%.3f").to_string())
        .unwrap_or_default()
}

/// 清理残留 frpc 进程（按命令行包含 run_dir 匹配）；返回清理数量。
fn kill_orphans(run_dir: &std::path::Path) -> usize {
    let token = run_dir.to_string_lossy().to_string();
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Stdio;
        let script = format!(
            "$mine='{token}'\nGet-CimInstance Win32_Process -Filter \"Name='frpc.exe'\" | \
             Where-Object {{ $_.CommandLine -like \"*$mine*\" }} | \
             ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $_.ProcessId }}"
        );
        match std::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .creation_flags(CREATE_NO_WINDOW)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
        {
            Ok(o) => String::from_utf8_lossy(&o.stdout)
                .lines()
                .filter(|l| l.trim().parse::<i64>().is_ok())
                .count(),
            Err(_) => 0,
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        use std::process::Stdio;
        let out = std::process::Command::new("pgrep")
            .args(["-f", &token])
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output();
        let pids: Vec<String> = match out {
            Ok(o) => String::from_utf8_lossy(&o.stdout)
                .lines()
                .filter(|l| l.trim().parse::<i64>().is_ok())
                .map(str::to_string)
                .collect(),
            Err(_) => return 0,
        };
        if pids.is_empty() {
            return 0;
        }
        let _ = std::process::Command::new("pkill")
            .args(["-f", &token])
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output();
        pids.len()
    }
}

// 注：Unix 上的优雅终止（SIGTERM，Kotlin destroy()）需要 libc/nix 依赖：
//   unsafe { libc::kill(pid, libc::SIGTERM) }
// 当前用 start_kill（SIGKILL / TerminateProcess）保证跨平台行为一致；
// frpc 对硬终止的容错由 frps 心跳超时兜底，e2e（M5）会验证隧道可恢复。

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_doubles_and_caps() {
        assert_eq!(2_000, backoff_ms(1, 2_000, 60_000));
        assert_eq!(4_000, backoff_ms(2, 2_000, 60_000));
        assert_eq!(8_000, backoff_ms(3, 2_000, 60_000));
        assert_eq!(60_000, backoff_ms(7, 2_000, 60_000), "超过上限应钳制");
    }

    /// 回归：在**无 tokio 上下文**的普通线程调用 start（复现 Tauri 命令线程场景，
    /// 修复"点击启动后闪退"）。
    #[test]
    fn start_works_without_tokio_context() {
        let dir = std::env::temp_dir().join(format!("frpc-sup-noctx-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let engine = make_fake_engine(&dir);
        let sup = Supervisor::new(SupervisorConfig {
            engine_path: engine,
            run_dir: dir.clone(),
            run_config_file: dir.join("run.toml"),
            status_interval: Duration::from_millis(50),
            metrics_interval: Duration::from_millis(50),
            max_log_lines: 50,
            max_restarts: 1,
            backoff_base_ms: 10,
            backoff_cap_ms: 50,
        });
        let state = sup.state();
        // 当前线程没有任何 tokio 运行时 —— 修复前这里会 panic
        sup.start(ProfileRef {
            id: "p1".into(),
            name: "n".into(),
            config: FrpcConfig {
                server_addr: "1.2.3.4".into(),
                ..FrpcConfig::default()
            },
        });
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            assert!(Instant::now() < deadline, "等待 RUNNING 超时");
            if state.borrow().phase == Phase::Running {
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        sup.stop();
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            assert!(Instant::now() < deadline, "等待 IDLE 超时");
            if state.borrow().phase == Phase::Idle {
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn check_before_start_validates() {
        let sup = Supervisor::new(SupervisorConfig::default());
        let blank = FrpcConfig::default();
        assert!(sup.check_before_start(&blank).is_some(), "空地址应拦截");

        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port() as i32;
        let busy = FrpcConfig {
            server_addr: "frps.example.com".into(),
            web_server: Some(crate::model::WebServerConfig {
                port,
                ..crate::model::WebServerConfig::default()
            }),
            ..FrpcConfig::default()
        };
        let msg = sup.check_before_start(&busy).expect("端口占用应拦截");
        assert!(msg.contains(&port.to_string()), "{msg}");
        drop(listener);

        let ok = FrpcConfig {
            server_addr: "frps.example.com".into(),
            ..FrpcConfig::default()
        };
        assert!(sup.check_before_start(&ok).is_none());
    }

    /// 真实进程生命周期：假引擎（忽略参数、保持存活）start → RUNNING → stop → IDLE。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn lifecycle_start_running_stop() {
        let dir = std::env::temp_dir().join(format!("frpc-sup-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let engine = make_fake_engine(&dir);

        let cfg = SupervisorConfig {
            engine_path: engine,
            run_dir: dir.clone(),
            run_config_file: dir.join("run.toml"),
            status_interval: Duration::from_millis(50),
            metrics_interval: Duration::from_millis(50),
            max_log_lines: 100,
            max_restarts: 2,
            backoff_base_ms: 10,
            backoff_cap_ms: 50,
        };
        let sup = Arc::new(Supervisor::new(cfg));
        let mut state = sup.state();
        sup.start(ProfileRef {
            id: "p1".into(),
            name: "测试".into(),
            config: FrpcConfig {
                server_addr: "1.2.3.4".into(),
                ..FrpcConfig::default()
            },
        });

        let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
        loop {
            assert!(
                tokio::time::Instant::now() < deadline,
                "等待 RUNNING 超时，state = {:?}",
                state.borrow()
            );
            if state.borrow_and_update().phase == Phase::Running {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(state.borrow().pid.is_some(), "RUNNING 状态应带 pid");

        sup.stop();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            assert!(
                tokio::time::Instant::now() < deadline,
                "等待 IDLE 超时，state = {:?}",
                state.borrow()
            );
            if state.borrow_and_update().phase == Phase::Idle {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(sup
            .log_snapshot()
            .iter()
            .any(|l| l.message.contains("已停止 frpc")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn make_fake_engine(dir: &std::path::Path) -> PathBuf {
        #[cfg(target_os = "windows")]
        {
            let path = dir.join("fake-engine.cmd");
            std::fs::write(&path, "@echo off\r\nping -n 120 127.0.0.1 > NUL\r\n").unwrap();
            path
        }
        #[cfg(not(target_os = "windows"))]
        {
            use std::os::unix::fs::PermissionsExt;
            let path = dir.join("fake-engine.sh");
            std::fs::write(&path, "#!/bin/sh\nsleep 120\n").unwrap();
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
            path
        }
    }
}
