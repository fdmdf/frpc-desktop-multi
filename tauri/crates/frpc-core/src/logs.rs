//! 日志：分级解析、代理状态事件、错误提示码。
//!
//! Kotlin 来源：
//! - `desktopApp/.../engine/LogParser.kt`（FrpcLogParser / ProxyLogParser）
//! - `shared/src/commonMain/.../logs/FrpcErrorHints.kt`
//!
//! 架构差异（有意为之）：Kotlin 的 `hintFor` 直接返回中文文案；
//! Rust 核心层返回结构化 [`ErrorHint`]（错误码 + 提取到的代理名），
//! 双语文案由前端 i18n 承担 —— 对拍测试相应改为错误码断言。

use chrono::{Local, TimeZone};
use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum LogProxyPhase {
    Pending,
    Running,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogProxyState {
    pub phase: LogProxyPhase,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum ProxyLogEvent {
    Update { name: String, state: LogProxyState },
    VisitorsRegistered { names: Vec<String> },
    LoginSuccess,
    LoginFailed { error: String },
    Reset,
}

/// 单行日志（Kotlin `LogLine`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    pub time_ms: i64,
    pub level: LogLevel,
    pub tag: String,
    pub message: String,
}

pub struct FrpcLogParser;

impl FrpcLogParser {
    /// 解析一行 frpc 输出；无法识别结构时整行作为 INFO 消息。
    pub fn parse(raw: &str, now_fallback_ms: i64) -> LogLine {
        static ANSI: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static PATTERN: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        let ansi = ANSI.get_or_init(|| Regex::new("\x1b\\[[;\\d]*[A-Za-z]").expect("ansi"));
        let pattern = PATTERN.get_or_init(|| {
            Regex::new(r"^(\d{4}[-/]\d{2}[-/]\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([TDIWE])\] \[([^\]]*)] ?(.*)$")
                .expect("pattern")
        });

        let cleaned = ansi.replace_all(raw.trim_end(), "").to_string();
        if let Some(caps) = pattern.captures(&cleaned) {
            let timestamp = &caps[1];
            let level_char = &caps[2];
            let tag = caps[3].to_string();
            let message = caps.get(4).map(|m| m.as_str()).unwrap_or("").to_string();
            let time_ms = parse_local_ms(timestamp).unwrap_or(now_fallback_ms);
            return LogLine {
                time_ms,
                level: level_of(level_char),
                tag,
                message,
            };
        }
        LogLine {
            time_ms: now_fallback_ms,
            level: LogLevel::Info,
            tag: String::new(),
            message: cleaned,
        }
    }

    pub fn is_access_log(message: &str) -> bool {
        message.starts_with("http request: [") || message.starts_with("http response [")
    }
}

fn level_of(c: &str) -> LogLevel {
    match c {
        "T" => LogLevel::Trace,
        "D" => LogLevel::Debug,
        "W" => LogLevel::Warn,
        "E" => LogLevel::Error,
        _ => LogLevel::Info,
    }
}

/// 本地时区语义与 Kotlin `LocalDateTime.parse(..., ZoneId.systemDefault())` 一致。
fn parse_local_ms(timestamp: &str) -> Option<i64> {
    let naive = chrono::NaiveDateTime::parse_from_str(timestamp, "%Y-%m-%d %H:%M:%S%.3f")
        .or_else(|_| chrono::NaiveDateTime::parse_from_str(timestamp, "%Y/%m/%d %H:%M:%S%.3f"))
        .ok()?;
    Local
        .from_local_datetime(&naive)
        .earliest()
        .map(|t| t.timestamp_millis())
}

pub struct ProxyLogParser;

impl ProxyLogParser {
    /// 从日志消息中提取代理状态事件（Kotlin `ProxyLogParser.parse`）。
    pub fn parse(message: &str) -> Option<ProxyLogEvent> {
        static LOGIN_FAIL: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static LOGIN_OK: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static ADDED: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static STARTED: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static ERROR: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static VISITOR_ADDED: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        let login_fail =
            LOGIN_FAIL.get_or_init(|| Regex::new(r"connect to server error: (.+)").expect("re"));
        let login_ok = LOGIN_OK.get_or_init(|| Regex::new(r"login to server success").expect("re"));
        let added = ADDED.get_or_init(|| Regex::new(r"proxy added: \[([^\]]+)]").expect("re"));
        let started =
            STARTED.get_or_init(|| Regex::new(r"\[([^\]]+)] start proxy success").expect("re"));
        let error = ERROR.get_or_init(|| Regex::new(r"\[([^\]]+)] start error: (.+)").expect("re"));
        let visitor_added =
            VISITOR_ADDED.get_or_init(|| Regex::new(r"visitor added: \[([^\]]*)]").expect("re"));

        if let Some(m) = login_fail.captures(message) {
            return Some(ProxyLogEvent::LoginFailed {
                error: m[1].trim().to_string(),
            });
        }
        if login_ok.is_match(message) {
            return Some(ProxyLogEvent::LoginSuccess);
        }
        if let Some(m) = added.captures(message) {
            return Some(ProxyLogEvent::Update {
                name: m[1].to_string(),
                state: LogProxyState {
                    phase: LogProxyPhase::Pending,
                    error: None,
                },
            });
        }
        if let Some(m) = started.captures(message) {
            return Some(ProxyLogEvent::Update {
                name: m[1].to_string(),
                state: LogProxyState {
                    phase: LogProxyPhase::Running,
                    error: None,
                },
            });
        }
        if let Some(m) = error.captures(message) {
            return Some(ProxyLogEvent::Update {
                name: m[1].to_string(),
                state: LogProxyState {
                    phase: LogProxyPhase::Error,
                    error: Some(m[2].trim().to_string()),
                },
            });
        }
        if let Some(m) = visitor_added.captures(message) {
            let names: Vec<String> = m[1]
                .split(' ')
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .collect();
            if !names.is_empty() {
                return Some(ProxyLogEvent::VisitorsRegistered { names });
            }
        }
        const RESET_MARKERS: [&str; 2] = ["control writer is closing", "client exit success"];
        if RESET_MARKERS.iter().any(|m| message.contains(m)) {
            return Some(ProxyLogEvent::Reset);
        }
        None
    }
}

/// 错误提示码（前端 i18n 键）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HintCode {
    /// frps 上不存在对应的 xtcp 代理（常为前缀/user 写错），`name` 为实际查找的名字
    XtcpServerMissing,
    /// frps 上不存在对应代理，`name` 为实际查找的名字
    ProxyMissing,
    StunTimeout,
    NotEnoughAddresses,
    NatHoleExchangeInfo,
    NatHolePrecheckEof,
    OpenTunnelTimeout,
    XtcpListenerClosed,
    TokenMismatch,
    LoginIoTimeout,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorHint {
    pub code: HintCode,
    /// 从日志中提取的代理名（如有）。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

pub struct FrpcErrorHints;

impl FrpcErrorHints {
    pub fn hint_for(raw: &str) -> Option<ErrorHint> {
        static XTCP_MISSING: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        static PROXY_MISSING: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        let xtcp_missing = XTCP_MISSING
            .get_or_init(|| Regex::new(r#"xtcp server for \[([^]]+)] doesn't exist"#).expect("re"));
        let proxy_missing =
            PROXY_MISSING.get_or_init(|| Regex::new(r"proxy \[([^]]+)] not found").expect("re"));

        if let Some(m) = xtcp_missing.captures(raw) {
            return Some(ErrorHint {
                code: HintCode::XtcpServerMissing,
                name: Some(m[1].to_string()),
            });
        }
        if let Some(m) = proxy_missing.captures(raw) {
            return Some(ErrorHint {
                code: HintCode::ProxyMissing,
                name: Some(m[1].to_string()),
            });
        }
        let code = if raw.contains("wait response from stun server timeout") {
            HintCode::StunTimeout
        } else if raw.contains("discover error: not enough addresses") {
            HintCode::NotEnoughAddresses
        } else if raw.contains("nathole exchange info error")
            || raw.contains("get natHoleRespMsg error: context deadline exceeded")
        {
            HintCode::NatHoleExchangeInfo
        } else if raw.contains("nathole precheck error") && raw.contains("EOF") {
            HintCode::NatHolePrecheckEof
        } else if raw.contains("open tunnel timeout") {
            HintCode::OpenTunnelTimeout
        } else if raw.contains("xtcp local listener closed")
            || raw.contains("xtcp internal listener closed")
        {
            HintCode::XtcpListenerClosed
        } else if raw.contains("token in login doesn't match") {
            HintCode::TokenMismatch
        } else if raw.contains("i/o timeout") && raw.contains("login to server") {
            HintCode::LoginIoTimeout
        } else {
            return None;
        };
        Some(ErrorHint { code, name: None })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---------- 对拍 Kotlin FrpcErrorHintsTest（错误码断言） ----------

    #[test]
    fn xtcp_server_missing_extracts_name() {
        let raw = "10:16:06.825 [W] [3dc8cf8f8daea3cc] [testx] nathole precheck error: \
                   xtcp server for [小米14.testx] doesn't exist";
        let hint = FrpcErrorHints::hint_for(raw).expect("应有提示");
        assert_eq!(HintCode::XtcpServerMissing, hint.code);
        assert_eq!(
            Some("小米14.testx".to_string()),
            hint.name,
            "应带上实际查找的名字"
        );
    }

    #[test]
    fn stun_timeout_detected() {
        let hint = FrpcErrorHints::hint_for(
            "[x] nathole prepare error: discover error: wait response from stun server timeout",
        )
        .expect("应有提示");
        assert_eq!(HintCode::StunTimeout, hint.code);
    }

    #[test]
    fn deadline_exceeded_detected() {
        let hint = FrpcErrorHints::hint_for(
            "[testx] nathole exchange info error: get natHoleRespMsg error: context deadline exceeded",
        )
        .expect("应有提示");
        assert_eq!(HintCode::NatHoleExchangeInfo, hint.code);
    }

    #[test]
    fn normal_lines_have_no_hint() {
        assert!(FrpcErrorHints::hint_for("login to server success, get run id [abc]").is_none());
        assert!(FrpcErrorHints::hint_for("[test] start proxy success").is_none());
        assert!(FrpcErrorHints::hint_for("visitor added: [testx]").is_none());
    }

    // ---------- FrpcLogParser / ProxyLogParser 语义 ----------

    #[test]
    fn parse_structured_line() {
        let now = 1_700_000_000_000;
        let line = FrpcLogParser::parse(
            "2026/10/31 10:16:06.825 [W] [testx] nathole precheck error",
            now,
        );
        assert_eq!(LogLevel::Warn, line.level);
        assert_eq!("testx", line.tag);
        assert_eq!("nathole precheck error", line.message);
        assert!(line.time_ms != now, "结构化时间应被解析");
    }

    #[test]
    fn parse_unstructured_line_falls_back() {
        let now = 1_700_000_000_123;
        let line = FrpcLogParser::parse("plain text line", now);
        assert_eq!(LogLevel::Info, line.level);
        assert_eq!("", line.tag);
        assert_eq!("plain text line", line.message);
        assert_eq!(now, line.time_ms);
    }

    #[test]
    fn strips_ansi_escapes() {
        let now = 1;
        let line = FrpcLogParser::parse("\x1b[31mlogin to server success\x1b[0m", now);
        assert_eq!("login to server success", line.message);
    }

    #[test]
    fn access_log_detection() {
        assert!(FrpcLogParser::is_access_log("http request: [GET /x]"));
        assert!(FrpcLogParser::is_access_log("http response [200]"));
        assert!(!FrpcLogParser::is_access_log("start proxy success"));
    }

    #[test]
    fn proxy_events() {
        assert_eq!(
            Some(ProxyLogEvent::Update {
                name: "ssh".into(),
                state: LogProxyState {
                    phase: LogProxyPhase::Running,
                    error: None
                }
            }),
            ProxyLogParser::parse("[ssh] start proxy success")
        );
        assert_eq!(
            Some(ProxyLogEvent::Update {
                name: "ssh".into(),
                state: LogProxyState {
                    phase: LogProxyPhase::Pending,
                    error: None
                }
            }),
            ProxyLogParser::parse("proxy added: [ssh]")
        );
        assert_eq!(
            Some(ProxyLogEvent::Update {
                name: "ssh".into(),
                state: LogProxyState {
                    phase: LogProxyPhase::Error,
                    error: Some("port already used".into())
                }
            }),
            ProxyLogParser::parse("[ssh] start error: port already used")
        );
        assert_eq!(
            Some(ProxyLogEvent::LoginSuccess),
            ProxyLogParser::parse("login to server success, get run id [x]")
        );
        assert_eq!(
            Some(ProxyLogEvent::LoginFailed {
                error: "dial tcp 1.2.3.4:7000: i/o timeout".into()
            }),
            ProxyLogParser::parse("connect to server error: dial tcp 1.2.3.4:7000: i/o timeout")
        );
        assert_eq!(
            Some(ProxyLogEvent::VisitorsRegistered {
                names: vec!["a".into(), "b".into()]
            }),
            ProxyLogParser::parse("visitor added: [a b]")
        );
        assert_eq!(
            Some(ProxyLogEvent::Reset),
            ProxyLogParser::parse("control writer is closing")
        );
        assert_eq!(None, ProxyLogParser::parse("nothing special"));
    }
}
