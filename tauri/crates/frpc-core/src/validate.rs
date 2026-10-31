//! 配置校验与端口批量解析。
//!
//! Kotlin 来源（逐规则对译）：
//! - `shared/src/commonMain/.../validate/ConfigValidator.kt`
//! - `shared/src/commonMain/.../ports/PortRange.kt`
//!
//! 注意：校验器产出结构化 `Reason` + 字段路径，文案由前端 i18n 承担；
//! `detail` 中的少量说明文字与 Kotlin 保持逐字一致（含中文），以便对拍。

use serde::{Deserialize, Serialize};

use crate::model::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Severity {
    Error,
    Warning,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Scope {
    Global,
    Auth,
    WebServer,
    Transport,
    Log,
    Proxy,
    Visitor,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Reason {
    EmptyRequiredField,
    PortOutOfRange,
    PortInUse,
    InvalidValue,
    DuplicateName,
    TypeFieldMissing,
    DomainOrSubdomainRequired,
    UnsupportedValue,
    FieldNotSupportedForType,

    BindPortRequired,
    SubdomainInvalid,
    MultiplexerInvalid,
    BandwidthLimitInvalid,

    XtcpStunServerNotSet,
    FallbackVisitorNotFound,

    ServerNamePrefixDoubled,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ValidationIssue {
    pub severity: Severity,
    pub scope: Scope,
    pub field: String,
    pub reason: Reason,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub entity_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

impl ValidationIssue {
    fn new(severity: Severity, scope: Scope, field: &str, reason: Reason) -> Self {
        Self {
            severity,
            scope,
            field: field.to_string(),
            reason,
            entity_name: None,
            detail: None,
        }
    }

    fn entity(mut self, name: &str) -> Self {
        self.entity_name = Some(name.to_string());
        self
    }

    fn detail(mut self, detail: &str) -> Self {
        self.detail = Some(detail.to_string());
        self
    }
}

pub struct ConfigValidator;

impl ConfigValidator {
    pub fn validate(config: &FrpcConfig, port_in_use: impl Fn(i32) -> bool) -> Vec<ValidationIssue> {
        let mut issues = Vec::new();
        const PORT_MIN: i32 = 1;
        const PORT_MAX: i32 = 65535;

        if config.server_addr.trim().is_empty() {
            issues.push(ValidationIssue::new(
                Severity::Error,
                Scope::Global,
                "serverAddr",
                Reason::EmptyRequiredField,
            ));
        }
        check_port(config.server_port, "serverPort", Scope::Global, &mut issues);

        let auth_method = config
            .auth
            .as_ref()
            .map(|a| if a.method.trim().is_empty() { "none" } else { a.method.as_str() })
            .unwrap_or("none");
        match auth_method {
            "token" => {
                if config
                    .auth
                    .as_ref()
                    .map(|a| a.token.trim().is_empty())
                    .unwrap_or(true)
                {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Auth,
                        "auth.token",
                        Reason::EmptyRequiredField,
                    ));
                }
            }
            "none" => {}
            "oidc" => issues.push(ValidationIssue::new(
                Severity::Warning,
                Scope::Auth,
                "auth.method",
                Reason::UnsupportedValue,
            )),
            _ => issues.push(ValidationIssue::new(
                Severity::Error,
                Scope::Auth,
                "auth.method",
                Reason::InvalidValue,
            )),
        }

        if let Some(ws) = &config.web_server {
            if ws.addr.trim().is_empty() {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::WebServer,
                    "webServer.addr",
                    Reason::EmptyRequiredField,
                ));
            }
            check_port(ws.port, "webServer.port", Scope::WebServer, &mut issues);
            if (PORT_MIN..=PORT_MAX).contains(&ws.port) && port_in_use(ws.port) {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::WebServer,
                    "webServer.port",
                    Reason::PortInUse,
                ));
            }
        }

        if let Some(t) = &config.transport {
            if !matches!(t.protocol.as_str(), "tcp" | "kcp" | "quic" | "websocket" | "wss") {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Transport,
                    "transport.protocol",
                    Reason::InvalidValue,
                ));
            }
            if t.pool_count < 0 {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Transport,
                    "transport.poolCount",
                    Reason::InvalidValue,
                ));
            }
            if t.dial_server_timeout < 0 {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Transport,
                    "transport.dialServerTimeout",
                    Reason::InvalidValue,
                ));
            }
            if let Some(v) = t.heartbeat_interval {
                if v <= 0 {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Transport,
                        "transport.heartbeatInterval",
                        Reason::InvalidValue,
                    ));
                }
            }
            if let Some(v) = t.heartbeat_timeout {
                if v <= 0 {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Transport,
                        "transport.heartbeatTimeout",
                        Reason::InvalidValue,
                    ));
                }
            }
            if let Some(v) = t.tcp_mux_keepalive_interval {
                if v < 0 {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Transport,
                        "transport.tcpMuxKeepaliveInterval",
                        Reason::InvalidValue,
                    ));
                }
            }
            if let Some(tls) = &t.tls {
                let cert = tls.cert_file.as_deref().filter(|s| !s.trim().is_empty());
                let key = tls.key_file.as_deref().filter(|s| !s.trim().is_empty());
                if cert.is_some() != key.is_some() {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Transport,
                        "transport.tls.certFile",
                        Reason::InvalidValue,
                    )
                    .detail("certFile 与 keyFile 必须同时填写"));
                }
            }
        }

        if let Some(l) = &config.log {
            if !matches!(l.level.as_str(), "trace" | "debug" | "info" | "warn" | "error") {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Log,
                    "log.level",
                    Reason::InvalidValue,
                ));
            }
        }

        let mut names = std::collections::HashSet::new();
        for (index, proxy) in config.proxies.iter().enumerate() {
            let prefix = format!("proxies[{index}]");
            let prefix = prefix.as_str();
            if proxy.name.trim().is_empty() {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.name"),
                    Reason::EmptyRequiredField,
                ));
            } else if !names.insert(proxy.name.clone()) {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.name"),
                    Reason::DuplicateName,
                )
                .entity(&proxy.name));
            }
            let Some(t) = proxy.proxy_type() else {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.type"),
                    Reason::InvalidValue,
                )
                .entity(&proxy.name));
                continue;
            };
            if t.needs_local_addr() {
                if proxy.local_ip.as_deref().map(str::trim).unwrap_or("").is_empty() {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Proxy,
                        &format!("{prefix}.localIP"),
                        Reason::EmptyRequiredField,
                    )
                    .entity(&proxy.name));
                }
                if proxy.local_port.map(|p| (PORT_MIN..=PORT_MAX).contains(&p)) != Some(true) {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Proxy,
                        &format!("{prefix}.localPort"),
                        Reason::PortOutOfRange,
                    )
                    .entity(&proxy.name));
                }
            }
            if t.needs_remote_port()
                && proxy.remote_port.map(|p| (0..=PORT_MAX).contains(&p)) == Some(false)
            {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.remotePort"),
                    Reason::PortOutOfRange,
                )
                .entity(&proxy.name));
            }
            if t.needs_secret_key()
                && proxy.secret_key.as_deref().map(str::trim).unwrap_or("").is_empty()
            {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.secretKey"),
                    Reason::EmptyRequiredField,
                )
                .entity(&proxy.name));
            }
            if t.domain_based() {
                let has_domain = proxy
                    .custom_domains
                    .as_ref()
                    .map(|d| !d.is_empty())
                    .unwrap_or(false)
                    || proxy.subdomain.as_deref().map(str::trim).unwrap_or("").len() > 0;
                if !has_domain {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Proxy,
                        &format!("{prefix}.customDomains"),
                        Reason::DomainOrSubdomainRequired,
                    )
                    .entity(&proxy.name));
                }
            }
            if t == ProxyType::Tcpmux && proxy.multiplexer.as_deref().unwrap_or("").is_empty() {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.multiplexer"),
                    Reason::EmptyRequiredField,
                )
                .entity(&proxy.name));
            }
            if t == ProxyType::Tcpmux
                && proxy.multiplexer.as_deref().unwrap_or("").len() > 0
                && proxy.multiplexer.as_deref() != Some("httpconnect")
            {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.multiplexer"),
                    Reason::MultiplexerInvalid,
                )
                .entity(&proxy.name)
                .detail("唯一可选值 httpconnect"));
            }
            if t.domain_based()
                && proxy
                    .subdomain
                    .as_deref()
                    .map(|s| !s.is_empty() && (s.contains('.') || s.contains('*')))
                    .unwrap_or(false)
            {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.subdomain"),
                    Reason::SubdomainInvalid,
                )
                .entity(&proxy.name)
                .detail("只填一级名字，例如 web01"));
            }
            let bandwidth = proxy
                .transport
                .as_ref()
                .and_then(|tr| tr.bandwidth_limit.as_deref())
                .map(str::trim)
                .unwrap_or("");
            if !bandwidth.is_empty() && !is_bandwidth_limit(bandwidth) {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.transport.bandwidthLimit"),
                    Reason::BandwidthLimitInvalid,
                )
                .entity(&proxy.name)
                .detail("例如 1MB、512KB"));
            }
            if proxy.remote_port.is_some() && !t.needs_remote_port() {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.remotePort"),
                    Reason::FieldNotSupportedForType,
                )
                .entity(&proxy.name));
            }
            if !t.domain_based() && proxy.custom_domains.as_ref().map(|d| !d.is_empty()) == Some(true)
            {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.customDomains"),
                    Reason::FieldNotSupportedForType,
                )
                .entity(&proxy.name));
            }
            // 与 Kotlin 行为对齐：上面列表里 "subdomain" 一项因 `present == true`
            // 的类型比较（String 与 Boolean）永远不成立，非 domain 类型带
            // subdomain 实际不会被标记 —— 保留该行为，不擅自"修复"。
            if !t.needs_secret_key()
                && (proxy.secret_key.as_deref().map(|s| !s.is_empty()).unwrap_or(false)
                    || proxy.allow_users.as_ref().map(|u| !u.is_empty()).unwrap_or(false))
            {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.secretKey"),
                    Reason::FieldNotSupportedForType,
                )
                .entity(&proxy.name));
            }
            if !t.supports_multiplexer() && proxy.multiplexer.as_deref().unwrap_or("").len() > 0 {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Proxy,
                    &format!("{prefix}.multiplexer"),
                    Reason::FieldNotSupportedForType,
                )
                .entity(&proxy.name));
            }
            if let Some(plugin) = &proxy.plugin {
                if plugin.type_.trim().is_empty() {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Proxy,
                        &format!("{prefix}.plugin.type"),
                        Reason::EmptyRequiredField,
                    )
                    .entity(&proxy.name));
                } else if plugin.is_https_plugin() && t != ProxyType::Https {
                    issues.push(ValidationIssue::new(
                        Severity::Error,
                        Scope::Proxy,
                        &format!("{prefix}.plugin"),
                        Reason::FieldNotSupportedForType,
                    )
                    .entity(&proxy.name)
                    .detail(&format!("{} 只能用于 https 代理", plugin.type_)));
                }
                if plugin.is_https_plugin() {
                    if plugin.local_addr.as_deref().map(str::trim).unwrap_or("").is_empty() {
                        issues.push(ValidationIssue::new(
                            Severity::Error,
                            Scope::Proxy,
                            &format!("{prefix}.plugin.localAddr"),
                            Reason::EmptyRequiredField,
                        )
                        .entity(&proxy.name));
                    }
                    if plugin.crt_path.as_deref().map(str::trim).unwrap_or("").is_empty() {
                        issues.push(ValidationIssue::new(
                            Severity::Error,
                            Scope::Proxy,
                            &format!("{prefix}.plugin.crtPath"),
                            Reason::EmptyRequiredField,
                        )
                        .entity(&proxy.name));
                    }
                    if plugin.key_path.as_deref().map(str::trim).unwrap_or("").is_empty() {
                        issues.push(ValidationIssue::new(
                            Severity::Error,
                            Scope::Proxy,
                            &format!("{prefix}.plugin.keyPath"),
                            Reason::EmptyRequiredField,
                        )
                        .entity(&proxy.name));
                    }
                }
            }
        }

        let visitor_names: std::collections::HashSet<&str> =
            config.visitors.iter().map(|v| v.name.as_str()).collect();
        for (index, visitor) in config.visitors.iter().enumerate() {
            let prefix = format!("visitors[{index}]");
            let prefix = prefix.as_str();
            if visitor.name.trim().is_empty() {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Visitor,
                    &format!("{prefix}.name"),
                    Reason::EmptyRequiredField,
                ));
            }
            if !matches!(visitor.type_.as_str(), "stcp" | "xtcp" | "sudp") {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Visitor,
                    &format!("{prefix}.type"),
                    Reason::InvalidValue,
                )
                .entity(&visitor.name));
            }
            if visitor.server_name.trim().is_empty() {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Visitor,
                    &format!("{prefix}.serverName"),
                    Reason::EmptyRequiredField,
                )
                .entity(&visitor.name));
            }
            if visitor.bind_port == 0 {
                issues.push(ValidationIssue::new(
                    Severity::Error,
                    Scope::Visitor,
                    &format!("{prefix}.bindPort"),
                    Reason::BindPortRequired,
                )
                .entity(&visitor.name)
                .detail("不监听请填 -1"));
            }
            if let Some(fallback) = visitor.fallback_to.as_deref().filter(|s| !s.is_empty()) {
                if !visitor_names.contains(fallback) {
                    issues.push(ValidationIssue::new(
                        Severity::Warning,
                        Scope::Visitor,
                        &format!("{prefix}.fallbackTo"),
                        Reason::FallbackVisitorNotFound,
                    )
                    .entity(&visitor.name));
                }
            }
            let user_prefix = visitor
                .server_user
                .as_deref()
                .filter(|s| !s.is_empty())
                .or_else(|| config.user.as_deref().filter(|s| !s.is_empty()));
            if let Some(_prefix_user) = user_prefix {
                if is_cross_user_server_name(&visitor.server_name) {
                    let actual = target_server_proxy_name(
                        config.user.as_deref(),
                        visitor.server_user.as_deref(),
                        &visitor.server_name,
                    );
                    issues.push(ValidationIssue::new(
                        Severity::Warning,
                        Scope::Visitor,
                        &format!("{prefix}.serverName"),
                        Reason::ServerNamePrefixDoubled,
                    )
                    .entity(&visitor.name)
                    .detail(&format!("当前会查找「{actual}」")));
                }
            }
        }

        let uses_xtcp = config.proxies.iter().any(|p| p.type_ == "xtcp")
            || config.visitors.iter().any(|v| v.type_ == "xtcp");
        if uses_xtcp && config.nat_hole_stun_server.as_deref().unwrap_or("").is_empty() {
            issues.push(ValidationIssue::new(
                Severity::Warning,
                Scope::Global,
                "natHoleStunServer",
                Reason::XtcpStunServerNotSet,
            ));
        }

        issues
    }

    pub fn has_error(issues: &[ValidationIssue]) -> bool {
        issues.iter().any(|i| i.severity == Severity::Error)
    }
}

fn is_cross_user_server_name(server_name: &str) -> bool {
    match server_name.find('.') {
        Some(dot) => dot > 0 && dot < server_name.len() - 1,
        None => false,
    }
}

fn is_bandwidth_limit(value: &str) -> bool {
    let unit = if let Some(stripped) = value.strip_suffix("MB") {
        stripped
    } else if let Some(stripped) = value.strip_suffix("KB") {
        stripped
    } else {
        return false;
    };
    !unit.is_empty()
        && unit.chars().all(|c| c.is_ascii_digit() || c == '.')
        && unit.matches('.').count() <= 1
        && unit.parse::<f64>().is_ok()
}

fn check_port(port: i32, field: &str, scope: Scope, issues: &mut Vec<ValidationIssue>) {
    if !(1..=65535).contains(&port) {
        issues.push(ValidationIssue::new(
            Severity::Error,
            scope,
            field,
            Reason::PortOutOfRange,
        ));
    }
}

/// 端口批量表达式解析（Kotlin `PortRange`）。
pub struct PortRange;

impl PortRange {
    pub const MAX_EXPAND: usize = 100;

    pub fn is_batch(expr: &str) -> bool {
        expr.contains(',') || expr.contains('，') || expr.contains('-')
    }

    pub fn proxy_name(base: &str, port: i32) -> String {
        let trimmed = base.trim();
        let trimmed = if trimmed.is_empty() { "proxy" } else { trimmed };
        format!("{trimmed}-{port}")
    }

    pub fn parse(expr: &str) -> ParseResult {
        Self::parse_with(expr, Self::MAX_EXPAND)
    }

    pub fn parse_with(expr: &str, max_expand: usize) -> ParseResult {
        let tokens: Vec<&str> = expr
            .split(|c| c == ',' || c == '，' || c == ' ' || c == '\n' || c == '\t')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .collect();
        if tokens.is_empty() {
            return ParseResult::Failed {
                reason: PortReason::Empty,
                detail: None,
            };
        }

        const PORT_MIN: i64 = 1;
        const PORT_MAX: i64 = 65535;
        let mut ports: Vec<i64> = Vec::new();
        for token in tokens {
            let token = token;
            match token.find('-') {
                None => {
                    let Ok(port) = token.parse::<i64>() else {
                        return ParseResult::Failed {
                            reason: PortReason::Invalid,
                            detail: Some(token.to_string()),
                        };
                    };
                    if !(PORT_MIN..=PORT_MAX).contains(&port) {
                        return ParseResult::Failed {
                            reason: PortReason::OutOfRange,
                            detail: Some(token.to_string()),
                        };
                    }
                    if !ports.contains(&port) {
                        ports.push(port);
                    }
                }
                Some(dash) => {
                    let Ok(start) = token[..dash].trim().parse::<i64>() else {
                        return ParseResult::Failed {
                            reason: PortReason::Invalid,
                            detail: Some(token.to_string()),
                        };
                    };
                    let Ok(end) = token[dash + 1..].trim().parse::<i64>() else {
                        return ParseResult::Failed {
                            reason: PortReason::Invalid,
                            detail: Some(token.to_string()),
                        };
                    };
                    if !(PORT_MIN..=PORT_MAX).contains(&start) || !(PORT_MIN..=PORT_MAX).contains(&end)
                    {
                        return ParseResult::Failed {
                            reason: PortReason::OutOfRange,
                            detail: Some(token.to_string()),
                        };
                    }
                    if start > end {
                        return ParseResult::Failed {
                            reason: PortReason::Reversed,
                            detail: Some(token.to_string()),
                        };
                    }
                    let span = end - start + 1;
                    if span > max_expand as i64 - ports.len() as i64 {
                        return ParseResult::Failed {
                            reason: PortReason::TooMany,
                            detail: Some(token.to_string()),
                        };
                    }
                    for p in start..=end {
                        if !ports.contains(&p) {
                            ports.push(p);
                        }
                    }
                }
            }
            if ports.len() > max_expand {
                return ParseResult::Failed {
                    reason: PortReason::TooMany,
                    detail: Some(token.to_string()),
                };
            }
        }
        ParseResult::Ok(ports.into_iter().map(|p| p as i32).collect())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ParseResult {
    Ok(Vec<i32>),
    Failed {
        reason: PortReason,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        detail: Option<String>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PortReason {
    Empty,
    Invalid,
    OutOfRange,
    Reversed,
    TooMany,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_config() -> FrpcConfig {
        FrpcConfig {
            server_addr: "frps.example.com".into(),
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "tok".into(),
            }),
            ..FrpcConfig::default()
        }
    }

    // ---------- 对拍 Kotlin ConfigValidatorTest ----------

    #[test]
    fn valid_config_no_issues() {
        let config = FrpcConfig {
            web_server: Some(WebServerConfig {
                port: 7400,
                ..WebServerConfig::default()
            }),
            proxies: vec![
                ProxyConfig {
                    name: "ssh".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    remote_port: Some(6001),
                    ..ProxyConfig::bare()
                },
                ProxyConfig {
                    name: "web".into(),
                    type_: "http".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(80),
                    subdomain: Some("web".into()),
                    ..ProxyConfig::bare()
                },
                ProxyConfig {
                    name: "st".into(),
                    type_: "stcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    secret_key: Some("k".into()),
                    ..ProxyConfig::bare()
                },
            ],
            ..base_config()
        };
        let issues = ConfigValidator::validate(&config, |_| false);
        assert!(issues.is_empty(), "unexpected issues: {issues:?}");
    }

    #[test]
    fn xtcp_without_stun_server_warning() {
        let with_proxy = FrpcConfig {
            proxies: vec![ProxyConfig {
                name: "x".into(),
                type_: "xtcp".into(),
                local_ip: Some("127.0.0.1".into()),
                local_port: Some(22),
                secret_key: Some("k".into()),
                ..ProxyConfig::bare()
            }],
            ..base_config()
        };
        assert!(
            ConfigValidator::validate(&with_proxy, |_| false)
                .iter()
                .any(|i| i.reason == Reason::XtcpStunServerNotSet),
            "xtcp 代理未填 STUN 应给警告"
        );
        let with_stun = FrpcConfig {
            nat_hole_stun_server: Some("stun.miwifi.com:3478".into()),
            ..with_proxy.clone()
        };
        assert!(
            ConfigValidator::validate(&with_stun, |_| false)
                .iter()
                .all(|i| i.reason != Reason::XtcpStunServerNotSet),
            "填了 STUN 就不该再警告"
        );

        let with_visitor = FrpcConfig {
            visitors: vec![VisitorConfig {
                name: "v".into(),
                type_: "xtcp".into(),
                server_name: "x".into(),
                secret_key: Some("k".into()),
                ..VisitorConfig::bare()
            }],
            ..base_config()
        };
        assert!(
            ConfigValidator::validate(&with_visitor, |_| false)
                .iter()
                .any(|i| i.reason == Reason::XtcpStunServerNotSet),
            "xtcp 访问者未填 STUN 同样应给警告"
        );
    }

    #[test]
    fn fallback_visitor_not_found_warning() {
        let ok = FrpcConfig {
            visitors: vec![
                VisitorConfig {
                    name: "x".into(),
                    type_: "xtcp".into(),
                    server_name: "p".into(),
                    secret_key: Some("k".into()),
                    fallback_to: Some("x-stcp".into()),
                    ..VisitorConfig::bare()
                },
                VisitorConfig {
                    name: "x-stcp".into(),
                    type_: "stcp".into(),
                    server_name: "p".into(),
                    secret_key: Some("k".into()),
                    ..VisitorConfig::bare()
                },
            ],
            ..base_config()
        };
        assert!(
            ConfigValidator::validate(&ok, |_| false)
                .iter()
                .all(|i| i.reason != Reason::FallbackVisitorNotFound),
            "兜底访问者存在时不该警告"
        );

        let broken = FrpcConfig {
            visitors: vec![VisitorConfig {
                name: "x".into(),
                type_: "xtcp".into(),
                server_name: "p".into(),
                secret_key: Some("k".into()),
                fallback_to: Some("拼错了".into()),
                ..VisitorConfig::bare()
            }],
            ..base_config()
        };
        assert!(
            ConfigValidator::validate(&broken, |_| false)
                .iter()
                .any(|i| i.reason == Reason::FallbackVisitorNotFound),
            "兜底访问者不存在应给警告"
        );
    }

    #[test]
    fn server_name_with_prefix_warns_double_prefix() {
        let config = FrpcConfig {
            user: Some("小米14".into()),
            visitors: vec![VisitorConfig {
                name: "testx".into(),
                type_: "xtcp".into(),
                server_name: "ThinkBook16+.testx".into(),
                secret_key: Some("k".into()),
                ..VisitorConfig::bare()
            }],
            ..base_config()
        };
        let hit = ConfigValidator::validate(&config, |_| false)
            .into_iter()
            .find(|i| i.reason == Reason::ServerNamePrefixDoubled)
            .expect("应有双前缀警告");
        assert!(
            hit.detail.as_deref().unwrap_or("").contains("小米14.ThinkBook16+.testx"),
            "警告要写出实际查找的名字：{:?}",
            hit.detail
        );

        let ok = FrpcConfig {
            visitors: vec![VisitorConfig {
                server_name: "testx".into(),
                server_user: Some("ThinkBook16+".into()),
                ..config.visitors[0].clone()
            }],
            ..config.clone()
        };
        assert!(
            ConfigValidator::validate(&ok, |_| false)
                .iter()
                .all(|i| i.reason != Reason::ServerNamePrefixDoubled),
            "正确写法不该警告"
        );
    }

    #[test]
    fn missing_server_addr_and_token_errors() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                server_addr: String::new(),
                auth: Some(AuthConfig {
                    method: "token".into(),
                    token: String::new(),
                }),
                ..FrpcConfig::default()
            },
            |_| false,
        );
        assert!(issues
            .iter()
            .any(|i| i.scope == Scope::Global && i.reason == Reason::EmptyRequiredField));
        assert!(issues
            .iter()
            .any(|i| i.scope == Scope::Auth && i.reason == Reason::EmptyRequiredField));
        assert!(ConfigValidator::has_error(&issues));
    }

    #[test]
    fn web_server_port_in_use_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                web_server: Some(WebServerConfig {
                    port: 7400,
                    ..WebServerConfig::default()
                }),
                ..base_config()
            },
            |p| p == 7400,
        );
        assert!(issues
            .iter()
            .any(|i| i.reason == Reason::PortInUse && i.field == "webServer.port"));
    }

    #[test]
    fn duplicate_proxy_name_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![
                    ProxyConfig {
                        name: "ssh".into(),
                        type_: "tcp".into(),
                        local_ip: Some("127.0.0.1".into()),
                        local_port: Some(22),
                        remote_port: Some(6001),
                        ..ProxyConfig::bare()
                    },
                    ProxyConfig {
                        name: "ssh".into(),
                        type_: "tcp".into(),
                        local_ip: Some("127.0.0.1".into()),
                        local_port: Some(22),
                        remote_port: Some(6002),
                        ..ProxyConfig::bare()
                    },
                ],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues
            .iter()
            .any(|i| i.reason == Reason::DuplicateName && i.entity_name.as_deref() == Some("ssh")));
    }

    #[test]
    fn http_proxy_without_domain_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "web".into(),
                    type_: "http".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(80),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues.iter().any(|i| i.reason == Reason::DomainOrSubdomainRequired));
    }

    #[test]
    fn remote_port_zero_is_allowed_for_random_assignment() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "ssh".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    remote_port: Some(0),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues.iter().all(|i| !i.field.contains("remotePort")));
    }

    #[test]
    fn remote_port_empty_is_allowed_for_random_assignment() {
        let tcp = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "web".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(8080),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(tcp.iter().all(|i| !i.field.contains("remotePort")));

        let udp = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "dns".into(),
                    type_: "udp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(53),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(udp.iter().all(|i| !i.field.contains("remotePort")));
    }

    #[test]
    fn remote_port_out_of_range_still_rejected() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "web".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(8080),
                    remote_port: Some(70000),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues
            .iter()
            .any(|i| i.field.contains("remotePort") && i.reason == Reason::PortOutOfRange));
    }

    #[test]
    fn stcp_without_secret_key_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "st".into(),
                    type_: "stcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues
            .iter()
            .any(|i| i.reason == Reason::EmptyRequiredField && i.field.ends_with("secretKey")));
    }

    #[test]
    fn invalid_proxy_type_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "x".into(),
                    type_: "magic".into(),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues
            .iter()
            .any(|i| i.field.ends_with(".type") && i.reason == Reason::InvalidValue));
    }

    #[test]
    fn port_out_of_range_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "ssh".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(99999),
                    remote_port: Some(6001),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert_eq!(1, issues.iter().filter(|i| i.reason == Reason::PortOutOfRange).count());
    }

    #[test]
    fn auth_none_is_valid_without_token() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                auth: Some(AuthConfig {
                    method: "none".into(),
                    token: String::new(),
                }),
                ..base_config()
            },
            |_| false,
        );
        assert!(issues.iter().all(|i| i.scope != Scope::Auth), "auth=none 不应报错：{issues:?}");
    }

    #[test]
    fn negative_keepalive_is_allowed() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                transport: Some(TransportConfig {
                    dial_server_keepalive: -1,
                    ..TransportConfig::default()
                }),
                ..base_config()
            },
            |_| false,
        );
        assert!(issues.iter().all(|i| i.field != "transport.dialServerTimeout"), "{issues:?}");
    }

    #[test]
    fn heartbeat_must_be_positive() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                transport: Some(TransportConfig {
                    heartbeat_interval: Some(0),
                    heartbeat_timeout: Some(-5),
                    ..TransportConfig::default()
                }),
                ..base_config()
            },
            |_| false,
        );
        assert_eq!(2, issues.iter().filter(|i| i.reason == Reason::InvalidValue).count());
    }

    #[test]
    fn tls_cert_without_key_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                transport: Some(TransportConfig {
                    tls: Some(TlsConfig {
                        cert_file: Some("/tmp/a.crt".into()),
                        ..TlsConfig::default()
                    }),
                    ..TransportConfig::default()
                }),
                ..base_config()
            },
            |_| false,
        );
        assert!(
            issues
                .iter()
                .any(|i| i.field == "transport.tls.certFile" && i.reason == Reason::InvalidValue),
            "{issues:?}"
        );
    }

    #[test]
    fn https_plugin_missing_fields_error() {
        let plugin_issue = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "web".into(),
                    type_: "https".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(8080),
                    custom_domains: Some(vec!["a.example.com".into()]),
                    plugin: Some(ProxyPluginConfig {
                        type_: PLUGIN_HTTPS2HTTP.into(),
                        ..ProxyPluginConfig {
                            type_: String::new(),
                            local_addr: None,
                            crt_path: None,
                            key_path: None,
                            host_header_rewrite: None,
                        }
                    }),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert_eq!(
            3,
            plugin_issue
                .iter()
                .filter(|i| i.reason == Reason::EmptyRequiredField && i.field.contains("plugin"))
                .count(),
            "{plugin_issue:?}"
        );

        let ok = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "web".into(),
                    type_: "https".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(8080),
                    custom_domains: Some(vec!["a.example.com".into()]),
                    plugin: Some(ProxyPluginConfig {
                        type_: PLUGIN_TLS2RAW.into(),
                        local_addr: Some("127.0.0.1:8080".into()),
                        crt_path: Some("/tmp/a.crt".into()),
                        key_path: Some("/tmp/a.key".into()),
                        host_header_rewrite: None,
                    }),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(ok.iter().all(|i| i.scope != Scope::Proxy), "{ok:?}");
    }

    #[test]
    fn https_plugin_on_tcp_error() {
        let issues = ConfigValidator::validate(
            &FrpcConfig {
                proxies: vec![ProxyConfig {
                    name: "ssh".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    remote_port: Some(6001),
                    plugin: Some(ProxyPluginConfig {
                        type_: PLUGIN_HTTPS2HTTP.into(),
                        local_addr: Some("127.0.0.1:8080".into()),
                        crt_path: Some("/tmp/a.crt".into()),
                        key_path: Some("/tmp/a.key".into()),
                        host_header_rewrite: None,
                    }),
                    ..ProxyConfig::bare()
                }],
                ..base_config()
            },
            |_| false,
        );
        assert!(issues.iter().any(|i| i.field.ends_with(".plugin")
            && i.reason == Reason::FieldNotSupportedForType));
    }

    // ---------- 对拍 Kotlin ProxyTypeFieldTest（校验器部分） ----------

    #[test]
    fn remote_port_only_allowed_for_tcp_and_udp() {
        for t in [ProxyType::Tcp, ProxyType::Udp] {
            let issues = ConfigValidator::validate(
                &FrpcConfig {
                    proxies: vec![ProxyConfig {
                        name: "p".into(),
                        type_: t.id().into(),
                        local_ip: Some("127.0.0.1".into()),
                        local_port: Some(22),
                        remote_port: Some(6001),
                        ..ProxyConfig::bare()
                    }],
                    ..base_config()
                },
                |_| false,
            );
            assert!(
                issues
                    .iter()
                    .all(|i| i.reason != Reason::FieldNotSupportedForType),
                "{} 应允许 remotePort",
                t.id()
            );
        }
        for t in [ProxyType::Tcpmux, ProxyType::Stcp, ProxyType::Sudp, ProxyType::Xtcp, ProxyType::Http, ProxyType::Https] {
            let issues = ConfigValidator::validate(
                &FrpcConfig {
                    proxies: vec![ProxyConfig {
                        name: "p".into(),
                        type_: t.id().into(),
                        local_ip: Some("127.0.0.1".into()),
                        local_port: Some(80),
                        remote_port: Some(6001),
                        subdomain: Some("web".into()),
                        secret_key: Some("sk".into()),
                        multiplexer: Some("httpconnect".into()),
                        ..ProxyConfig::bare()
                    }],
                    ..base_config()
                },
                |_| false,
            );
            assert!(
                issues.iter().any(|i| i.reason == Reason::FieldNotSupportedForType),
                "{} 带 remotePort 应报 FIELD_NOT_SUPPORTED_FOR_TYPE",
                t.id()
            );
        }
    }

    // ---------- 对拍 Kotlin PortRangeTest ----------

    mod port_range {
        use super::*;

        fn ok(expr: &str) -> Vec<i32> {
            match PortRange::parse(expr) {
                ParseResult::Ok(ports) => ports,
                other => panic!("expected ok, got {other:?}"),
            }
        }

        fn failed(expr: &str) -> PortReason {
            match PortRange::parse(expr) {
                ParseResult::Failed { reason, .. } => reason,
                other => panic!("expected failed, got {other:?}"),
            }
        }

        #[test]
        fn single_port() {
            assert_eq!(vec![8080], ok("8080"));
            assert_eq!(vec![8080], ok(" 8080 "));
            assert!(!PortRange::is_batch("8080"));
        }

        #[test]
        fn comma_list() {
            assert_eq!(vec![8080, 8081, 9000], ok("8080,8081,9000"));
            assert_eq!(vec![8080, 8081], ok("8080，8081"));
            assert!(PortRange::is_batch("8080,8081"));
        }

        #[test]
        fn range() {
            assert_eq!(vec![6001, 6002, 6003], ok("6001-6003"));
            assert_eq!(vec![8000, 8001, 9000], ok("8000-8001,9000"));
            assert!(PortRange::is_batch("8000-8001"));
        }

        #[test]
        fn dedupe_keeps_order() {
            assert_eq!(vec![80, 81], ok("80,81,80"));
        }

        #[test]
        fn rejects_invalid() {
            assert_eq!(PortReason::Empty, failed(""));
            assert_eq!(PortReason::Invalid, failed("abc"));
            assert_eq!(PortReason::Invalid, failed("80-"));
            assert_eq!(PortReason::Invalid, failed("80-90-100"));
            assert_eq!(PortReason::Reversed, failed("9000-8000"));
            assert_eq!(PortReason::OutOfRange, failed("0"));
            assert_eq!(PortReason::OutOfRange, failed("70000"));
        }

        #[test]
        fn rejects_too_many() {
            assert_eq!(PortReason::TooMany, failed("1-65535"));
            assert_eq!(PortRange::MAX_EXPAND, ok("1000-1099").len());
            assert_eq!(PortReason::TooMany, failed("1000-1100"));
        }

        #[test]
        fn proxy_name_suffix() {
            assert_eq!("web-8080", PortRange::proxy_name("web", 8080));
            assert_eq!("proxy-22", PortRange::proxy_name("  ", 22));
        }
    }
}
