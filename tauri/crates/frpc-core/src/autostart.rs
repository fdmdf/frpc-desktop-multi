//! 开机自启动：Windows 注册表 / macOS LaunchAgent / Linux XDG autostart。
//!
//! 三个平台没有任何统一的「开机自启动」系统 API，各自只有一个约定俗成的落点，
//! 所以这里按平台分别实现，但刻意把两件事拆开：
//!
//! 1. **要写什么内容**：`AutostartSpec` 负责生成 plist / `.desktop` / 注册表命令行，
//!    全是纯函数，可在任意平台上单测（不需要真的跑在目标系统上）；
//! 2. **怎么落到系统**：`SystemOps` 负责读写文件、调用 `reg.exe`，测试里换成内存实现。
//!
//! 失败一律返回 `AutostartError`，由应用层记一条日志了事 —— 自启动是锦上添花的能力，
//! 绝不允许它把应用启动主流程带崩（无权限、只读家目录、被安全软件拦截都属常见）。
//!
//! 平台差异（细节见 `docs/autostart.md`）：
//!
//! | 平台 | 落点 | 触发时机 | 权限 |
//! |------|------|----------|------|
//! | Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` | 用户登录 | 无需管理员（HKCU） |
//! | macOS | `~/Library/LaunchAgents/<id>.plist` | 用户登录（Aqua 会话） | 家目录可写即可 |
//! | Linux | `$XDG_CONFIG_HOME/autostart/<app>.desktop` | 桌面环境登录 | 配置文件可写即可 |

use std::fmt;
use std::path::{Path, PathBuf};

/// 目标平台。用显式枚举而不是 `cfg!` 分支，是为了能在任意宿主机上测试三种平台的产物。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Platform {
    Windows,
    MacOs,
    Linux,
}

impl Platform {
    /// 当前进程所在平台；不支持的平台（如 Android / iOS）返回 `None`。
    pub fn current() -> Option<Self> {
        if cfg!(target_os = "windows") {
            Some(Platform::Windows)
        } else if cfg!(target_os = "macos") {
            Some(Platform::MacOs)
        } else if cfg!(all(
            unix,
            not(target_os = "macos"),
            not(target_os = "android")
        )) {
            Some(Platform::Linux)
        } else {
            None
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Platform::Windows => "windows",
            Platform::MacOs => "macos",
            Platform::Linux => "linux",
        }
    }

    /// 注册落点的技术名，前端据此显示「写到哪里去了」。
    pub fn backend(self) -> &'static str {
        match self {
            Platform::Windows => "registry",
            Platform::MacOs => "launch-agent",
            Platform::Linux => "xdg-autostart",
        }
    }
}

impl fmt::Display for Platform {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

// ---------- 错误 ----------

/// 自启动操作失败的原因。`code()` 给前端做分支（例如提示「需要授权」而不是「未知错误」）。
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AutostartError {
    /// 平台不支持（Android / iOS / 未知系统），或拿不到可执行文件路径
    #[error("当前平台不支持开机自启动")]
    Unsupported,
    /// 无权限写入（macOS 受 TCC 保护、Linux 家目录只读、Windows 组策略禁止写 Run 项）
    #[error("没有权限写入自启动项（{path}）：{detail}")]
    Permission { path: String, detail: String },
    /// 普通 IO 失败（磁盘满、路径不存在、文件被占用）
    #[error("写入自启动项失败（{path}）：{detail}")]
    Io { path: String, detail: String },
    /// 系统命令（reg.exe）执行失败或不存在
    #[error("系统命令执行失败：{detail}")]
    Command { detail: String },
}

impl AutostartError {
    /// 稳定的错误码，前端用 `settings.autostartErr.<code>` 取文案。
    pub fn code(&self) -> &'static str {
        match self {
            AutostartError::Unsupported => "unsupported",
            AutostartError::Permission { .. } => "permission",
            AutostartError::Io { .. } => "io",
            AutostartError::Command { .. } => "command",
        }
    }

    fn io(path: &Path, err: &std::io::Error) -> Self {
        let path = path.display().to_string();
        let detail = err.to_string();
        if err.kind() == std::io::ErrorKind::PermissionDenied {
            AutostartError::Permission { path, detail }
        } else {
            AutostartError::Io { path, detail }
        }
    }
}

/// 系统命令的退出情况（不直接依赖 `std::process::Output`，便于测试构造）。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CmdOutput {
    /// 0 = 成功；-1 = 拿不到退出码（被信号杀掉等）
    pub status: i32,
    pub stdout: String,
    pub stderr: String,
}

impl CmdOutput {
    pub fn ok(stdout: impl Into<String>) -> Self {
        Self {
            status: 0,
            stdout: stdout.into(),
            stderr: String::new(),
        }
    }

    pub fn fail(stderr: impl Into<String>) -> Self {
        Self {
            status: 1,
            stdout: String::new(),
            stderr: stderr.into(),
        }
    }

    pub fn success(&self) -> bool {
        self.status == 0
    }

    /// 错误信息优先取 stderr，空则取 stdout（reg.exe 在部分语言环境下把错误打到 stdout）。
    pub fn message(&self) -> String {
        let text = if self.stderr.trim().is_empty() {
            self.stdout.trim()
        } else {
            self.stderr.trim()
        };
        if text.is_empty() {
            format!("退出码 {}", self.status)
        } else {
            text.to_string()
        }
    }
}

// ---------- 系统能力抽象 ----------

/// 自启动需要的那一丁点系统能力。测试里换成内存实现即可覆盖三种平台。
pub trait SystemOps {
    fn read(&self, path: &Path) -> Option<String>;
    fn write(&self, path: &Path, content: &str) -> Result<(), AutostartError>;
    /// 返回 false 表示本来就不存在（注销时不算失败）
    fn remove(&self, path: &Path) -> Result<bool, AutostartError>;
    fn exists(&self, path: &Path) -> bool;
    fn run(&self, program: &str, args: &[String]) -> Result<CmdOutput, AutostartError>;
}

/// 真实系统实现。
pub struct RealSystem;

impl SystemOps for RealSystem {
    fn read(&self, path: &Path) -> Option<String> {
        std::fs::read_to_string(path).ok()
    }

    fn write(&self, path: &Path, content: &str) -> Result<(), AutostartError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| AutostartError::io(parent, &e))?;
        }
        std::fs::write(path, content).map_err(|e| AutostartError::io(path, &e))
    }

    fn remove(&self, path: &Path) -> Result<bool, AutostartError> {
        match std::fs::remove_file(path) {
            Ok(()) => Ok(true),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(AutostartError::io(path, &e)),
        }
    }

    fn exists(&self, path: &Path) -> bool {
        path.exists()
    }

    fn run(&self, program: &str, args: &[String]) -> Result<CmdOutput, AutostartError> {
        let mut cmd = std::process::Command::new(program);
        cmd.args(args);
        // GUI 进程里直接 spawn 控制台程序会闪一下黑框，Windows 上显式隐藏窗口
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let out = cmd.output().map_err(|e| AutostartError::Command {
            detail: format!("{program}：{e}"),
        })?;
        Ok(CmdOutput {
            status: out.status.code().unwrap_or(-1),
            stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
            stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
        })
    }
}

// ---------- 注册内容 ----------

/// 一次自启动注册所需的全部信息。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AutostartSpec {
    pub platform: Platform,
    /// 应用显示名：Windows 注册表值名、Linux .desktop 文件名
    pub app_name: String,
    /// 反向域名标识：LaunchAgent 的 Label 与文件名
    pub id: String,
    /// 要开机启动的可执行文件（绝对路径）
    pub exe_path: PathBuf,
    /// 附加启动参数（约定 `--autostart`，应用据此判断「我是被开机拉起的」）
    pub args: Vec<String>,
    /// 用户主目录：`~/Library/LaunchAgents`
    pub home_dir: PathBuf,
    /// XDG 配置目录：`$XDG_CONFIG_HOME` 或 `~/.config`
    pub config_dir: PathBuf,
}

impl AutostartSpec {
    pub fn new(
        platform: Platform,
        app_name: &str,
        id: &str,
        exe_path: PathBuf,
        args: Vec<String>,
        home_dir: PathBuf,
        config_dir: PathBuf,
    ) -> Self {
        Self {
            platform,
            app_name: app_name.to_string(),
            id: id.to_string(),
            exe_path,
            args,
            home_dir,
            config_dir,
        }
    }

    /// Windows：`HKCU` 而非 `HKLM` —— 当前用户级自启不需要管理员权限，
    /// 也不会因为「给所有用户装」而要求 UAC 提权。
    pub const WINDOWS_RUN_KEY: &'static str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";

    pub fn windows_value_name(&self) -> String {
        self.app_name.clone()
    }

    /// 注册表里写的命令行：`"C:\path\app.exe" --autostart`
    ///
    /// 可执行文件路径**总是**加引号（不判断有没有空格）：未加引号的命令行走的是
    /// 「Unquoted Service Path」那套解析规则，路径里一旦出现空格，系统可能先去执行
    /// `C:\Program.exe`。固定加引号既省掉这个风险，也让解析侧只需要处理一种形态。
    pub fn command_line(&self) -> String {
        let mut s = format!("\"{}\"", self.exe_path.display());
        for arg in &self.args {
            s.push(' ');
            s.push_str(&win_quote(arg));
        }
        s
    }

    pub fn launch_agent_path(&self) -> PathBuf {
        self.home_dir
            .join("Library")
            .join("LaunchAgents")
            .join(format!("{}.plist", self.id))
    }

    pub fn desktop_entry_path(&self) -> PathBuf {
        self.config_dir
            .join("autostart")
            .join(format!("{}.desktop", self.app_name))
    }

    /// 给人看的「注册到哪了」。
    pub fn location(&self) -> String {
        match self.platform {
            Platform::Windows => {
                format!("{}\\{}", Self::WINDOWS_RUN_KEY, self.windows_value_name())
            }
            Platform::MacOs => self.launch_agent_path().display().to_string(),
            Platform::Linux => self.desktop_entry_path().display().to_string(),
        }
    }

    // ---- 注册命令（Windows） ----

    pub fn reg_add_args(&self) -> Vec<String> {
        vec![
            "add".into(),
            Self::WINDOWS_RUN_KEY.into(),
            "/v".into(),
            self.windows_value_name(),
            "/t".into(),
            "REG_SZ".into(),
            "/d".into(),
            self.command_line(),
            "/f".into(),
        ]
    }

    pub fn reg_delete_args(&self) -> Vec<String> {
        vec![
            "delete".into(),
            Self::WINDOWS_RUN_KEY.into(),
            "/v".into(),
            self.windows_value_name(),
            "/f".into(),
        ]
    }

    pub fn reg_query_args(&self) -> Vec<String> {
        vec![
            "query".into(),
            Self::WINDOWS_RUN_KEY.into(),
            "/v".into(),
            self.windows_value_name(),
        ]
    }

    // ---- 注册内容（macOS / Linux） ----

    pub fn render_plist(&self) -> String {
        let mut program = String::new();
        program.push_str(&format!(
            "        <string>{}</string>\n",
            xml_escape(&self.exe_path.display().to_string())
        ));
        for arg in &self.args {
            program.push_str(&format!("        <string>{}</string>\n", xml_escape(arg)));
        }
        format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- 由 frpc-desktop-multi 生成：开机（登录）时自动启动。删除本文件或关闭应用内开关即可取消。 -->
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>{label}</string>
    <key>ProgramArguments</key>
    <array>
{program}    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
    <key>ProcessType</key>
    <string>Interactive</string>
    <key>LimitLoadToSessionType</key>
    <string>Aqua</string>
</dict>
</plist>
"#,
            label = xml_escape(&self.id),
            program = program
        )
    }

    pub fn render_desktop_entry(&self) -> String {
        let mut exec = exec_escape(&self.exe_path.display().to_string());
        for arg in &self.args {
            exec.push(' ');
            exec.push_str(&exec_escape(arg));
        }
        format!(
            "[Desktop Entry]\n\
             Type=Application\n\
             Name={name}\n\
             Comment=frp client GUI - start at login\n\
             Exec={exec}\n\
             Terminal=false\n\
             X-GNOME-Autostart-enabled=true\n\
             Hidden=false\n\
             NoDisplay=false\n",
            name = self.app_name,
            exec = exec
        )
    }
}

// ---------- 状态 ----------

/// 自启动的**系统真实状态**（不是用户意图）。界面开关必须读这个值，
/// 否则会出现「界面显示已开启、实际开机没启动」这种最招人烦的错位。
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AutostartStatus {
    /// "windows" / "macos" / "linux"
    pub platform: String,
    /// 平台不支持时为 false，前端据此禁用开关
    pub supported: bool,
    /// 系统里确实存在注册项
    pub enabled: bool,
    /// 系统里记录的启动目标（可能为空：注册项存在但读不出来）
    pub target: Option<String>,
    /// 当前进程的可执行文件路径
    pub exe_path: String,
    /// 已注册但指向的**不是**当前程序：升级换了安装目录 / 用户手动改了路径
    pub stale: bool,
    /// "registry" / "launch-agent" / "xdg-autostart" / "none"
    pub backend: String,
    /// 注册落点（注册表键、plist 路径、desktop 文件路径）
    pub location: String,
    /// 读取注册项时的异常说明（权限、文件损坏等），正常为 None
    pub detail: Option<String>,
}

impl AutostartStatus {
    /// 平台不支持时的占位结构：字段齐全，前端可以直接渲染（开关置灰）。
    pub fn unsupported(platform: Option<Platform>, note: Option<String>) -> Self {
        Self {
            platform: platform.map(|p| p.as_str().to_string()).unwrap_or_default(),
            supported: false,
            enabled: false,
            target: None,
            exe_path: String::new(),
            stale: false,
            backend: platform
                .map(|p| p.backend().to_string())
                .unwrap_or_else(|| "none".into()),
            location: String::new(),
            detail: note,
        }
    }

    pub fn for_spec(spec: &AutostartSpec) -> Self {
        Self {
            platform: spec.platform.as_str().to_string(),
            supported: true,
            enabled: false,
            target: None,
            exe_path: spec.exe_path.display().to_string(),
            stale: false,
            backend: spec.platform.backend().to_string(),
            location: spec.location(),
            detail: None,
        }
    }
}

// ---------- 操作 ----------

/// 写入注册项（覆盖已有项）。
pub fn enable(spec: &AutostartSpec, sys: &dyn SystemOps) -> Result<(), AutostartError> {
    match spec.platform {
        Platform::Windows => {
            let out = sys.run("reg.exe", &spec.reg_add_args())?;
            if out.success() {
                Ok(())
            } else {
                Err(command_error(&out, &spec.location()))
            }
        }
        Platform::MacOs => sys.write(&spec.launch_agent_path(), &spec.render_plist()),
        Platform::Linux => sys.write(&spec.desktop_entry_path(), &spec.render_desktop_entry()),
    }
}

/// 删除注册项。本来就不存在不算失败 —— 用户点「关闭」的目的已经达成。
pub fn disable(spec: &AutostartSpec, sys: &dyn SystemOps) -> Result<(), AutostartError> {
    match spec.platform {
        Platform::Windows => {
            let out = sys.run("reg.exe", &spec.reg_delete_args())?;
            if out.success() {
                return Ok(());
            }
            // 值不存在时 `reg delete` 返回 1（"系统找不到指定的注册表项或值"）。
            // 用户要的结果（没有这个自启项）已经达成，不能报错吓人 —— 回读一次确认。
            match read_target(spec, sys) {
                Ok(None) => Ok(()),
                Err(e @ AutostartError::Permission { .. }) => Err(e),
                _ => Err(command_error(&out, &spec.location())),
            }
        }
        Platform::MacOs => {
            sys.remove(&spec.launch_agent_path())?;
            Ok(())
        }
        Platform::Linux => {
            sys.remove(&spec.desktop_entry_path())?;
            Ok(())
        }
    }
}

/// 读取系统里的真实注册状态。读失败也返回结构体（`detail` 带原因），不抛错 ——
/// 界面总要能渲染出来。
pub fn status(spec: &AutostartSpec, sys: &dyn SystemOps) -> AutostartStatus {
    let mut st = AutostartStatus::for_spec(spec);
    match read_target(spec, sys) {
        Ok(Some(target)) => {
            st.enabled = true;
            st.stale = !same_path(&target, &st.exe_path);
            st.target = Some(target);
        }
        Ok(None) => {}
        Err(e) => st.detail = Some(e.to_string()),
    }
    st
}

/// 开机自启动项的「自愈」结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReconcileAction {
    /// 用户没启用，什么都不做
    Disabled,
    /// 已启用且指向当前程序
    Unchanged,
    /// 指向旧路径（程序被移动 / 升级换了目录），已重写
    Repaired { previous: Option<String> },
    /// 已启用但系统里没有注册项（被手动清理或被系统重置），已补回
    Restored,
    /// 修复失败
    Failed { message: String },
}

/// 应用启动时对齐「用户意图」与「系统注册项」。
///
/// 这是**路径变更**的兜底：Windows 装在 `%LOCALAPPDATA%` 下、macOS 用 .app 包、
/// Linux 用 AppImage / deb，升级后安装路径都可能变；旧注册项还指向一个已被删除的
/// 可执行文件，表现就是「开关是开的，开机却没反应」。
///
/// 只做「补」不做「删」：`intent == false` 时绝不去动系统里的项，
/// 免得用户手动加过的启动项被应用悄悄抹掉。
pub fn reconcile(spec: &AutostartSpec, sys: &dyn SystemOps, intent: bool) -> ReconcileAction {
    if !intent {
        return ReconcileAction::Disabled;
    }
    let st = status(spec, sys);
    if st.enabled
        && st
            .target
            .as_deref()
            .is_some_and(|t| same_path(t, &st.exe_path))
    {
        return ReconcileAction::Unchanged;
    }
    match enable(spec, sys) {
        Ok(()) if st.enabled => ReconcileAction::Repaired {
            previous: st.target,
        },
        Ok(()) => ReconcileAction::Restored,
        Err(e) => ReconcileAction::Failed {
            message: e.to_string(),
        },
    }
}

// ---------- 内部：读取注册目标 ----------

fn read_target(
    spec: &AutostartSpec,
    sys: &dyn SystemOps,
) -> Result<Option<String>, AutostartError> {
    match spec.platform {
        Platform::Windows => {
            let out = sys.run("reg.exe", &spec.reg_query_args())?;
            if !out.success() {
                // 值不存在时 reg query 返回 1，这是正常情况；权限问题则要报出来
                if permission_like(&out.message()) {
                    return Err(AutostartError::Permission {
                        path: spec.location(),
                        detail: out.message(),
                    });
                }
                return Ok(None);
            }
            Ok(
                parse_reg_query_value(&out.stdout, &spec.windows_value_name())
                    .map(|data| command_line_target(&data)),
            )
        }
        Platform::MacOs => {
            let path = spec.launch_agent_path();
            match sys.read(&path) {
                // 文件在但解析不出 ProgramArguments（被改坏 / 手工编辑过）：
                // 仍算「已注册」，target 为空 → stale，交给 reconcile 重写
                Some(text) => Ok(Some(parse_plist_program(&text).unwrap_or_default())),
                None => Ok(None),
            }
        }
        Platform::Linux => {
            let path = spec.desktop_entry_path();
            match sys.read(&path) {
                // 桌面环境用 Hidden=true / X-GNOME-Autostart-enabled=false 表示
                // 「用户已停用这项自启」：文件还在，但开机确实不会启动
                Some(text) if desktop_entry_disabled(&text) => Ok(None),
                Some(text) => Ok(Some(parse_desktop_exec(&text).unwrap_or_default())),
                None => Ok(None),
            }
        }
    }
}

fn command_error(out: &CmdOutput, location: &str) -> AutostartError {
    let detail = out.message();
    if permission_like(&detail) {
        AutostartError::Permission {
            path: location.to_string(),
            detail,
        }
    } else {
        AutostartError::Command { detail }
    }
}

fn permission_like(text: &str) -> bool {
    let t = text.to_lowercase();
    [
        "access is denied",
        "access denied",
        "拒绝访问",
        "permission denied",
        "operation not permitted",
        "eacces",
    ]
    .iter()
    .any(|needle| t.contains(needle))
}

fn same_path(a: &str, b: &str) -> bool {
    let norm = |s: &str| {
        let s = s.trim().trim_matches('"').to_string();
        // 只有 Windows 的路径大小写不敏感、分隔符可混用
        if cfg!(windows) {
            s.replace('/', "\\").to_lowercase()
        } else {
            s
        }
    };
    norm(a) == norm(b)
}

// ---------- 内部：纯解析 / 转义 ----------

fn win_quote(s: &str) -> String {
    if s.is_empty() || s.contains(' ') || s.contains('\t') {
        format!("\"{s}\"")
    } else {
        s.to_string()
    }
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn xml_unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/// Desktop Entry 的 `Exec` 引号规则：含空格/特殊字符时用双引号包住，
/// 并对 `"`、`` ` ``、`$`、`\` 加反斜杠（与 GLib 的 g_shell_parse_argv 一致）。
fn exec_escape(s: &str) -> String {
    let needs_quote = s.is_empty()
        || s.chars()
            .any(|c| c.is_whitespace() || matches!(c, '"' | '\'' | '$' | '`' | '\\' | '#' | ';'));
    if !needs_quote {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        if matches!(c, '"' | '`' | '$' | '\\') {
            out.push('\\');
        }
        out.push(c);
    }
    out.push('"');
    out
}

fn exec_unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            if let Some(next) = chars.next() {
                out.push(next);
                continue;
            }
        }
        out.push(c);
    }
    out
}

/// `reg query` 输出里取某个值的数据部分。
///
/// 真实输出形如（行首是 4 个空格，列之间是多个空格）：
/// ```text
/// HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
///     frpc-desktop-multi    REG_SZ    "C:\app\frpc-desktop-multi.exe" --autostart
/// ```
/// 注意值本身含空格，不能用 `split_whitespace` 切第三段，只能定位类型标记再取其后全部内容。
pub fn parse_reg_query_value(stdout: &str, value_name: &str) -> Option<String> {
    let wanted = value_name.to_lowercase();
    for line in stdout.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let lower = trimmed.to_lowercase();
        if !lower.starts_with(&wanted) {
            continue;
        }
        // 名字之后必须是分隔（避免 `frpc-desktop-multi-old` 被误认）
        let rest = &trimmed[value_name.len()..];
        if !rest.starts_with(|c: char| c.is_whitespace()) {
            continue;
        }
        let upper = rest.to_uppercase();
        let (marker, marker_len) = if let Some(i) = upper.find("REG_EXPAND_SZ") {
            (i, "REG_EXPAND_SZ".len())
        } else if let Some(i) = upper.find("REG_SZ") {
            (i, "REG_SZ".len())
        } else if let Some(i) = upper.find("REG_NONE") {
            (i, "REG_NONE".len())
        } else {
            continue;
        };
        return Some(rest[marker + marker_len..].trim().to_string());
    }
    None
}

/// 从注册表命令行里取可执行文件路径：`"C:\a b\app.exe" --autostart` → `C:\a b\app.exe`
pub fn command_line_target(data: &str) -> String {
    let data = data.trim();
    if let Some(rest) = data.strip_prefix('"') {
        if let Some(end) = rest.find('"') {
            return rest[..end].to_string();
        }
        return rest.to_string();
    }
    data.split_whitespace().next().unwrap_or("").to_string()
}

/// 从 LaunchAgent plist 里取 `ProgramArguments` 的第一项（即可执行文件）。
pub fn parse_plist_program(text: &str) -> Option<String> {
    let key = text.find("<key>ProgramArguments</key>")?;
    let rest = &text[key..];
    let array = rest.find("<array>")?;
    let rest = &rest[array + "<array>".len()..];
    let start = rest.find("<string>")?;
    let rest = &rest[start + "<string>".len()..];
    let end = rest.find("</string>")?;
    Some(xml_unescape(rest[..end].trim()))
}

/// `.desktop` 里的 `Exec` 只取第一个参数；反斜杠转义在 `exec_target` 里就地处理。
pub fn parse_desktop_exec(text: &str) -> Option<String> {
    for line in text.lines() {
        let line = line.trim_end();
        if let Some(rest) = line.strip_prefix("Exec=") {
            return Some(exec_target(rest.trim()));
        }
    }
    None
}

/// .desktop 是否被「停用」：GNOME Tweaks / KDE 的启动管理器不会删文件，
/// 而是把 `X-GNOME-Autostart-enabled` 改成 false 或加上 `Hidden=true`。
pub fn desktop_entry_disabled(text: &str) -> bool {
    text.lines().any(|line| {
        let line = line.trim();
        let lower = line.to_lowercase();
        matches!(
            lower.as_str(),
            "hidden=true" | "x-gnome-autostart-enabled=false" | "autostart-enabled=false"
        )
    })
}

fn exec_target(exec: &str) -> String {
    if let Some(rest) = exec.strip_prefix('"') {
        // 先找到没有被转义的收尾引号，再统一反解（两段逻辑分开更好读，也不容易漏掉 `\\"`）
        let mut end = None;
        let mut escaped = false;
        for (i, c) in rest.char_indices() {
            if escaped {
                escaped = false;
                continue;
            }
            match c {
                '\\' => escaped = true,
                '"' => {
                    end = Some(i);
                    break;
                }
                _ => {}
            }
        }
        return exec_unescape(end.map(|e| &rest[..e]).unwrap_or(rest));
    }
    exec.split_whitespace().next().unwrap_or("").to_string()
}

/// 平台不支持的统一返回（应用层给前端兜底用）。
pub fn unsupported_status() -> AutostartStatus {
    AutostartStatus::unsupported(Platform::current(), None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::HashMap;

    // ---- 内存版 SystemOps：三种平台的产物都能在任意宿主机上验证 ----

    #[derive(Default)]
    struct FakeSystem {
        files: RefCell<HashMap<String, String>>,
        registry: RefCell<HashMap<String, String>>,
        commands: RefCell<Vec<String>>,
        deny_write: bool,
        no_reg: bool,
        reg_denied: bool,
    }

    impl FakeSystem {
        fn key(spec: &AutostartSpec) -> String {
            format!(
                "{}|{}",
                AutostartSpec::WINDOWS_RUN_KEY,
                spec.windows_value_name()
            )
        }
    }

    impl SystemOps for FakeSystem {
        fn read(&self, path: &Path) -> Option<String> {
            self.files
                .borrow()
                .get(&path.display().to_string())
                .cloned()
        }

        fn write(&self, path: &Path, content: &str) -> Result<(), AutostartError> {
            if self.deny_write {
                return Err(AutostartError::Permission {
                    path: path.display().to_string(),
                    detail: "Permission denied (os error 13)".into(),
                });
            }
            self.files
                .borrow_mut()
                .insert(path.display().to_string(), content.to_string());
            Ok(())
        }

        fn remove(&self, path: &Path) -> Result<bool, AutostartError> {
            Ok(self
                .files
                .borrow_mut()
                .remove(&path.display().to_string())
                .is_some())
        }

        fn exists(&self, path: &Path) -> bool {
            self.files
                .borrow()
                .contains_key(&path.display().to_string())
        }

        fn run(&self, program: &str, args: &[String]) -> Result<CmdOutput, AutostartError> {
            self.commands
                .borrow_mut()
                .push(format!("{program} {}", args.join(" ")));
            if self.no_reg {
                return Err(AutostartError::Command {
                    detail: "reg.exe：找不到文件".into(),
                });
            }
            if self.reg_denied {
                return Ok(CmdOutput::fail("错误: 拒绝访问。"));
            }
            let op = args.first().map(String::as_str).unwrap_or("");
            let name = args
                .iter()
                .position(|a| a == "/v")
                .and_then(|i| args.get(i + 1))
                .cloned()
                .unwrap_or_default();
            let key = format!("{}|{}", args.get(1).cloned().unwrap_or_default(), name);
            match op {
                "add" => {
                    let data = args
                        .iter()
                        .position(|a| a == "/d")
                        .and_then(|i| args.get(i + 1))
                        .cloned()
                        .unwrap_or_default();
                    self.registry.borrow_mut().insert(key, data);
                    Ok(CmdOutput::ok("操作成功完成。"))
                }
                "delete" => {
                    // 真实 reg.exe：值不存在时返回 1 + "系统找不到指定的注册表项或值"
                    if self.registry.borrow_mut().remove(&key).is_some() {
                        Ok(CmdOutput::ok("操作成功完成。"))
                    } else {
                        Ok(CmdOutput::fail("错误: 系统找不到指定的注册表项或值。"))
                    }
                }
                "query" => match self.registry.borrow().get(&key) {
                    Some(data) => Ok(CmdOutput::ok(format!(
                        "\r\n{}\r\n    {name}    REG_SZ    {data}\r\n\r\n",
                        args.get(1).cloned().unwrap_or_default()
                    ))),
                    None => Ok(CmdOutput {
                        status: 1,
                        stdout: String::new(),
                        stderr: "错误: 系统找不到指定的注册表项或值。".into(),
                    }),
                },
                _ => Ok(CmdOutput::fail("不支持的操作")),
            }
        }
    }

    fn spec(platform: Platform) -> AutostartSpec {
        AutostartSpec::new(
            platform,
            "frpc-desktop-multi",
            "io.github.fdmdf.frpc",
            PathBuf::from(match platform {
                Platform::Windows => r"C:\Users\liao\AppData\Local\frpc-desktop-multi\app.exe",
                _ => "/opt/frpc/frpc-desktop-multi",
            }),
            vec!["--autostart".into()],
            PathBuf::from("/home/liao"),
            PathBuf::from("/home/liao/.config"),
        )
    }

    fn mac_spec() -> AutostartSpec {
        AutostartSpec::new(
            Platform::MacOs,
            "frpc-desktop-multi",
            "io.github.fdmdf.frpc",
            PathBuf::from("/Applications/frpc-desktop-multi.app/Contents/MacOS/frpc-desktop-multi"),
            vec!["--autostart".into()],
            PathBuf::from("/Users/liao"),
            PathBuf::from("/Users/liao/.config"),
        )
    }

    // ---- 内容生成 ----

    #[test]
    fn plist_contains_label_and_program() {
        let s = mac_spec();
        let plist = s.render_plist();
        assert!(
            plist.contains("<string>io.github.fdmdf.frpc</string>"),
            "{plist}"
        );
        assert!(plist.contains("RunAtLoad"), "{plist}");
        assert!(plist.contains("<true/>"), "{plist}");
        assert!(plist.contains("--autostart"), "{plist}");
        assert_eq!(
            Some(s.exe_path.display().to_string()),
            parse_plist_program(&plist),
            "写进去的路径必须能被自己解析回来"
        );
    }

    #[test]
    fn plist_escapes_xml_specials() {
        let mut s = mac_spec();
        s.exe_path = PathBuf::from("/Users/a&b/<x>/app");
        let plist = s.render_plist();
        assert!(plist.contains("&amp;"), "{plist}");
        assert_eq!(
            Some("/Users/a&b/<x>/app".to_string()),
            parse_plist_program(&plist)
        );
    }

    #[test]
    fn desktop_entry_round_trips_path_with_spaces() {
        let mut s = spec(Platform::Linux);
        s.exe_path = PathBuf::from("/home/liao/My Apps/frpc desktop");
        let entry = s.render_desktop_entry();
        assert!(entry.contains("Type=Application"), "{entry}");
        assert!(entry.contains("X-GNOME-Autostart-enabled=true"), "{entry}");
        assert_eq!(
            Some("/home/liao/My Apps/frpc desktop".to_string()),
            parse_desktop_exec(&entry),
            "{entry}"
        );
    }

    #[test]
    fn desktop_entry_escapes_quotes_and_dollars() {
        let mut s = spec(Platform::Linux);
        s.exe_path = PathBuf::from(r#"/tmp/a"b$c/app"#);
        let entry = s.render_desktop_entry();
        assert_eq!(
            Some(r#"/tmp/a"b$c/app"#.to_string()),
            parse_desktop_exec(&entry),
            "{entry}"
        );
    }

    #[test]
    fn windows_command_line_is_quoted() {
        let s = spec(Platform::Windows);
        let line = s.command_line();
        assert!(line.starts_with('"'), "{line}");
        assert!(line.ends_with("--autostart"), "{line}");
        assert_eq!(s.exe_path.display().to_string(), command_line_target(&line));
    }

    #[test]
    fn legacy_unquoted_value_is_treated_as_stale() {
        // 老版本或手工写进注册表的项可能没有引号：只能解析到 C:\Program，
        // 与当前路径比对必然不一致 → 启动自愈时重写成带引号的形式
        assert_eq!(
            "C:\\Program",
            command_line_target(r"C:\Program Files\app\app.exe --autostart")
        );
    }

    #[test]
    fn reg_args_target_hkcu_and_force() {
        let s = spec(Platform::Windows);
        let add = s.reg_add_args();
        assert_eq!("add", add[0]);
        assert_eq!(AutostartSpec::WINDOWS_RUN_KEY, add[1]);
        assert!(
            AutostartSpec::WINDOWS_RUN_KEY.starts_with("HKCU\\"),
            "必须写用户级，避免 UAC"
        );
        assert!(add.iter().any(|a| a == "/f"));
        assert_eq!(
            Some(&"REG_SZ".to_string()),
            add.iter().find(|a| *a == "REG_SZ")
        );
        assert_eq!(
            vec![
                "delete",
                AutostartSpec::WINDOWS_RUN_KEY,
                "/v",
                "frpc-desktop-multi",
                "/f"
            ],
            s.reg_delete_args()
        );
    }

    // ---- 解析 ----

    #[test]
    fn parses_reg_query_output() {
        let out = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\r\n    \
                   frpc-desktop-multi    REG_SZ    \"C:\\Program Files\\app\\app.exe\" --autostart\r\n\r\n";
        let data = parse_reg_query_value(out, "frpc-desktop-multi").expect("应能找到值");
        assert_eq!("\"C:\\Program Files\\app\\app.exe\" --autostart", data);
        assert_eq!(
            "C:\\Program Files\\app\\app.exe",
            command_line_target(&data)
        );
        // 名字前缀相同但不是同一个值，不能误命中
        assert!(parse_reg_query_value(out, "frpc").is_none());
        assert!(parse_reg_query_value(out, "other").is_none());
    }

    #[test]
    fn parses_reg_expand_sz() {
        let out = "    SomeApp    REG_EXPAND_SZ    \"%LOCALAPPDATA%\\app.exe\"\r\n";
        assert_eq!(
            Some("\"%LOCALAPPDATA%\\app.exe\"".to_string()),
            parse_reg_query_value(out, "SomeApp")
        );
    }

    // ---- 启用 / 关闭 / 状态 ----

    #[test]
    fn windows_enable_disable_roundtrip() {
        let sys = FakeSystem::default();
        let s = spec(Platform::Windows);
        assert!(!status(&s, &sys).enabled);

        enable(&s, &sys).unwrap();
        let st = status(&s, &sys);
        assert!(st.enabled, "{st:?}");
        assert!(!st.stale, "刚注册的项不该被判成 stale：{st:?}");
        assert_eq!(Some(s.exe_path.display().to_string()), st.target);
        assert_eq!("registry", st.backend);

        disable(&s, &sys).unwrap();
        assert!(!status(&s, &sys).enabled);
    }

    #[test]
    fn macos_enable_writes_launch_agent() {
        let sys = FakeSystem::default();
        let s = mac_spec();
        enable(&s, &sys).unwrap();
        assert!(sys.exists(&s.launch_agent_path()), "应写入 LaunchAgents");
        let st = status(&s, &sys);
        assert!(st.enabled);
        assert_eq!("launch-agent", st.backend);
        assert_eq!(Some(s.exe_path.display().to_string()), st.target);

        disable(&s, &sys).unwrap();
        assert!(!status(&s, &sys).enabled);
        // 重复关闭不该报错（幂等）
        disable(&s, &sys).unwrap();
    }

    #[test]
    fn linux_enable_writes_xdg_entry() {
        let sys = FakeSystem::default();
        let s = spec(Platform::Linux);
        enable(&s, &sys).unwrap();
        assert!(sys.exists(&s.desktop_entry_path()));
        let st = status(&s, &sys);
        assert!(st.enabled);
        assert_eq!("xdg-autostart", st.backend);
        assert_eq!(Some(s.exe_path.display().to_string()), st.target);
        disable(&s, &sys).unwrap();
        assert!(!status(&s, &sys).enabled);
    }

    #[test]
    fn disable_is_idempotent_on_windows() {
        let sys = FakeSystem::default();
        let s = spec(Platform::Windows);
        enable(&s, &sys).unwrap();
        disable(&s, &sys).unwrap();
        // 第二次：reg.exe 会返回 1「找不到值」，但目标状态已达成，必须算成功
        disable(&s, &sys).expect("重复关闭不该报错");
        assert!(!status(&s, &sys).enabled);
    }

    // ---- 异常：无权限 / 命令缺失 ----

    #[test]
    fn permission_denied_is_reported_not_panicked() {
        let sys = FakeSystem {
            deny_write: true,
            ..Default::default()
        };
        let s = spec(Platform::Linux);
        let err = enable(&s, &sys).unwrap_err();
        assert_eq!("permission", err.code(), "{err}");
        assert!(!status(&s, &sys).enabled);
    }

    #[test]
    fn registry_access_denied_maps_to_permission() {
        let sys = FakeSystem {
            reg_denied: true,
            ..Default::default()
        };
        let s = spec(Platform::Windows);
        let err = enable(&s, &sys).unwrap_err();
        assert_eq!("permission", err.code(), "{err}");
        // 查询时被拒绝也要落到 detail，而不是假装「没启用」
        let st = status(&s, &sys);
        assert!(!st.enabled);
        assert!(
            st.detail.as_deref().unwrap_or("").contains("拒绝访问"),
            "{st:?}"
        );
    }

    #[test]
    fn missing_reg_exe_is_command_error() {
        let sys = FakeSystem {
            no_reg: true,
            ..Default::default()
        };
        let s = spec(Platform::Windows);
        let err = enable(&s, &sys).unwrap_err();
        assert_eq!("command", err.code(), "{err}");
    }

    // ---- 路径变更自愈 ----

    #[test]
    fn reconcile_repairs_moved_executable() {
        let sys = FakeSystem::default();
        let mut old = spec(Platform::Linux);
        old.exe_path = PathBuf::from("/opt/old/frpc-desktop-multi");
        enable(&old, &sys).unwrap();

        let new = spec(Platform::Linux); // /opt/frpc/frpc-desktop-multi
        assert!(status(&new, &sys).stale, "旧路径应判为 stale");

        let action = reconcile(&new, &sys, true);
        assert_eq!(
            ReconcileAction::Repaired {
                previous: Some("/opt/old/frpc-desktop-multi".into())
            },
            action
        );
        let st = status(&new, &sys);
        assert!(!st.stale, "{st:?}");
        assert_eq!(Some(new.exe_path.display().to_string()), st.target);
    }

    #[test]
    fn reconcile_restores_missing_entry() {
        let sys = FakeSystem::default();
        let s = spec(Platform::Windows);
        assert_eq!(ReconcileAction::Restored, reconcile(&s, &sys, true));
        assert!(status(&s, &sys).enabled);
        // 已经对了就别再动系统
        assert_eq!(ReconcileAction::Unchanged, reconcile(&s, &sys, true));
    }

    #[test]
    fn reconcile_never_deletes_when_disabled() {
        let sys = FakeSystem::default();
        let s = mac_spec();
        enable(&s, &sys).unwrap();
        assert_eq!(ReconcileAction::Disabled, reconcile(&s, &sys, false));
        assert!(
            status(&s, &sys).enabled,
            "用户没开启时不得擅自删除系统里已有的项"
        );
    }

    #[test]
    fn reconcile_failure_is_reported() {
        let sys = FakeSystem {
            deny_write: true,
            ..Default::default()
        };
        let s = spec(Platform::Linux);
        match reconcile(&s, &sys, true) {
            ReconcileAction::Failed { message } => assert!(message.contains("权限"), "{message}"),
            other => panic!("应返回失败：{other:?}"),
        }
    }

    #[test]
    fn unsupported_platform_status_is_not_panicking() {
        let st = unsupported_status();
        assert!(!st.supported);
        assert!(
            matches!(
                st.backend.as_str(),
                "registry" | "launch-agent" | "xdg-autostart" | "none"
            ),
            "backend 必须是已知值：{}",
            st.backend
        );
    }

    #[test]
    fn hidden_desktop_entry_counts_as_disabled() {
        let sys = FakeSystem::default();
        let s = spec(Platform::Linux);
        enable(&s, &sys).unwrap();
        assert!(status(&s, &sys).enabled);

        // 用户在 GNOME「启动应用程序」里关掉：文件保留，只改标记
        sys.files.borrow_mut().insert(
            s.desktop_entry_path().display().to_string(),
            "Type=Application\nHidden=true\n".into(),
        );
        assert!(!status(&s, &sys).enabled, "Hidden=true 表示开机不会启动");
    }
}
