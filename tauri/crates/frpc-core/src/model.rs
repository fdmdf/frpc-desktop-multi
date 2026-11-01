//! 配置模型：服务器档案、代理（8 种类型）与访问者（3 种类型）的数据结构。
//!
//! Kotlin 来源（逐字段对译）：
//! - `shared/src/commonMain/.../model/FrpcConfig.kt`
//! - `shared/src/commonMain/.../model/ProxyConfig.kt`
//! - `shared/src/commonMain/.../model/FrpcNaming.kt`
//!
//! 约定：字段名保持与 frpc.toml 一致（camelCase），serde 仅用于档案 JSON 存储；
//! TOML 编解码见 [`crate::toml`]（手工构建，控制省略与表布局）。

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

pub const PLUGIN_HTTPS2HTTP: &str = "https2http";
pub const PLUGIN_TLS2RAW: &str = "tls2raw";

fn some_if_non_blank(s: &str) -> Option<String> {
    let t = s.trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

/// 顶层客户端配置（Kotlin `FrpcConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrpcConfig {
    #[serde(default)]
    pub server_addr: String,
    #[serde(default = "default_server_port")]
    pub server_port: i32,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "clientID")]
    pub client_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user: Option<String>,
    #[serde(default)]
    pub login_fail_exit: bool,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "natHoleStunServer"
    )]
    pub nat_hole_stun_server: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub log: Option<LogConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth: Option<AuthConfig>,
    /// Kotlin 构造默认 `WebServerConfig()`（状态接口默认开启）；解码缺省时同样补默认值。
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "webServer")]
    pub web_server: Option<WebServerConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transport: Option<TransportConfig>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "udpPacketSize"
    )]
    pub udp_packet_size: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metadatas: Option<BTreeMap<String, String>>,
    #[serde(default)]
    pub proxies: Vec<ProxyConfig>,
    #[serde(default)]
    pub visitors: Vec<VisitorConfig>,
}

pub(crate) fn default_server_port() -> i32 {
    7000
}

impl Default for FrpcConfig {
    fn default() -> Self {
        Self {
            server_addr: String::new(),
            server_port: 7000,
            client_id: None,
            user: None,
            login_fail_exit: false,
            nat_hole_stun_server: None,
            log: None,
            auth: None,
            web_server: Some(WebServerConfig::default()),
            transport: None,
            udp_packet_size: None,
            metadatas: None,
            proxies: Vec::new(),
            visitors: Vec::new(),
        }
    }
}

/// `log.*`（Kotlin `LogConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogConfig {
    #[serde(default = "default_log_to")]
    pub to: String,
    #[serde(default = "default_log_level")]
    pub level: String,
    #[serde(default = "default_log_max_days")]
    pub max_days: i32,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "disablePrintColor"
    )]
    pub disable_print_color: Option<bool>,
}

pub(crate) fn default_log_to() -> String {
    "console".into()
}
pub(crate) fn default_log_level() -> String {
    "info".into()
}
pub(crate) fn default_log_max_days() -> i32 {
    3
}

impl Default for LogConfig {
    fn default() -> Self {
        Self {
            to: default_log_to(),
            level: default_log_level(),
            max_days: default_log_max_days(),
            disable_print_color: None,
        }
    }
}

/// `auth.*`（Kotlin `AuthConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthConfig {
    #[serde(default = "default_auth_method")]
    pub method: String,
    #[serde(default)]
    pub token: String,
}

pub(crate) fn default_auth_method() -> String {
    "token".into()
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            method: default_auth_method(),
            token: String::new(),
        }
    }
}

/// `webServer.*`（Kotlin `WebServerConfig`，frpc 的 admin API）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WebServerConfig {
    #[serde(default = "default_ws_addr")]
    pub addr: String,
    #[serde(default = "default_ws_port")]
    pub port: i32,
    #[serde(default = "default_ws_user")]
    pub user: String,
    #[serde(default = "default_ws_password")]
    pub password: String,
    #[serde(default, rename = "pprofEnable")]
    pub pprof_enable: bool,
}

pub(crate) fn default_ws_addr() -> String {
    "127.0.0.1".into()
}
pub(crate) fn default_ws_port() -> i32 {
    7400
}
pub(crate) fn default_ws_user() -> String {
    "admin".into()
}
pub(crate) fn default_ws_password() -> String {
    "admin".into()
}

impl Default for WebServerConfig {
    fn default() -> Self {
        Self {
            addr: default_ws_addr(),
            port: default_ws_port(),
            user: default_ws_user(),
            password: default_ws_password(),
            pprof_enable: false,
        }
    }
}

/// `transport.*`（Kotlin `TransportConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransportConfig {
    #[serde(default = "default_tr_protocol")]
    pub protocol: String,
    #[serde(default = "default_true", rename = "tcpMux")]
    pub tcp_mux: bool,
    #[serde(default, rename = "poolCount")]
    pub pool_count: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls: Option<TlsConfig>,
    #[serde(default = "default_dial_timeout", rename = "dialServerTimeout")]
    pub dial_server_timeout: i32,
    #[serde(default = "default_dial_keepalive", rename = "dialServerKeepalive")]
    pub dial_server_keepalive: i32,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "connectServerLocalIP"
    )]
    pub connect_server_local_ip: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "proxyURL")]
    pub proxy_url: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "heartbeatInterval"
    )]
    pub heartbeat_interval: Option<i32>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "heartbeatTimeout"
    )]
    pub heartbeat_timeout: Option<i32>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "tcpMuxKeepaliveInterval"
    )]
    pub tcp_mux_keepalive_interval: Option<i32>,
}

pub(crate) fn default_tr_protocol() -> String {
    "tcp".into()
}
pub(crate) fn default_true() -> bool {
    true
}
pub(crate) fn default_dial_timeout() -> i32 {
    10
}
pub(crate) fn default_dial_keepalive() -> i32 {
    7200
}

impl Default for TransportConfig {
    fn default() -> Self {
        Self {
            protocol: default_tr_protocol(),
            tcp_mux: true,
            pool_count: 0,
            tls: None,
            dial_server_timeout: default_dial_timeout(),
            dial_server_keepalive: default_dial_keepalive(),
            connect_server_local_ip: None,
            proxy_url: None,
            heartbeat_interval: None,
            heartbeat_timeout: None,
            tcp_mux_keepalive_interval: None,
        }
    }
}

/// `transport.tls.*`（Kotlin `TlsConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TlsConfig {
    #[serde(default = "default_true")]
    pub enable: bool,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "serverName"
    )]
    pub server_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "certFile")]
    pub cert_file: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "keyFile")]
    pub key_file: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "trustedCaFile"
    )]
    pub trusted_ca_file: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "disableCustomTLSFirstByte"
    )]
    pub disable_custom_tls_first_byte: Option<bool>,
}

impl Default for TlsConfig {
    fn default() -> Self {
        Self {
            enable: true,
            server_name: None,
            cert_file: None,
            key_file: None,
            trusted_ca_file: None,
            disable_custom_tls_first_byte: None,
        }
    }
}

/// 代理类型（Kotlin `ProxyType`），能力位与 Kotlin 一致。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ProxyType {
    Tcp,
    Udp,
    Http,
    Https,
    Stcp,
    Sudp,
    Xtcp,
    Tcpmux,
}

impl ProxyType {
    pub fn id(&self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
            Self::Http => "http",
            Self::Https => "https",
            Self::Stcp => "stcp",
            Self::Sudp => "sudp",
            Self::Xtcp => "xtcp",
            Self::Tcpmux => "tcpmux",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Some(match id {
            "tcp" => Self::Tcp,
            "udp" => Self::Udp,
            "http" => Self::Http,
            "https" => Self::Https,
            "stcp" => Self::Stcp,
            "sudp" => Self::Sudp,
            "xtcp" => Self::Xtcp,
            "tcpmux" => Self::Tcpmux,
            _ => return None,
        })
    }

    pub fn needs_remote_port(&self) -> bool {
        matches!(self, Self::Tcp | Self::Udp)
    }

    pub fn needs_local_addr(&self) -> bool {
        true
    }

    pub fn needs_secret_key(&self) -> bool {
        matches!(self, Self::Stcp | Self::Sudp | Self::Xtcp)
    }

    pub fn domain_based(&self) -> bool {
        matches!(self, Self::Http | Self::Https | Self::Tcpmux)
    }

    pub fn supports_http_advanced(&self) -> bool {
        *self == Self::Http
    }

    pub fn supports_http_auth(&self) -> bool {
        matches!(self, Self::Http | Self::Tcpmux)
    }

    pub fn supports_multiplexer(&self) -> bool {
        *self == Self::Tcpmux
    }

    pub fn supports_https_plugin(&self) -> bool {
        *self == Self::Https
    }
}

/// 单条代理（Kotlin `ProxyConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProxyConfig {
    pub name: String,
    #[serde(rename = "type")]
    pub type_: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "localIP")]
    pub local_ip: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "localPort")]
    pub local_port: Option<i32>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "remotePort"
    )]
    pub remote_port: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subdomain: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "customDomains"
    )]
    pub custom_domains: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locations: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "httpUser")]
    pub http_user: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "httpPassword"
    )]
    pub http_password: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "hostHeaderRewrite"
    )]
    pub host_header_rewrite: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "routeByHTTPUser"
    )]
    pub route_by_http_user: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub multiplexer: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "secretKey")]
    pub secret_key: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "allowUsers"
    )]
    pub allow_users: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transport: Option<ProxyTransportConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plugin: Option<ProxyPluginConfig>,
}

impl ProxyConfig {
    /// 测试辅助：空代理（name/type 为空串，其余 None）。
    pub fn bare() -> Self {
        Self {
            name: String::new(),
            type_: String::new(),
            enabled: None,
            local_ip: None,
            local_port: None,
            remote_port: None,
            subdomain: None,
            custom_domains: None,
            locations: None,
            http_user: None,
            http_password: None,
            host_header_rewrite: None,
            route_by_http_user: None,
            multiplexer: None,
            secret_key: None,
            allow_users: None,
            transport: None,
            plugin: None,
        }
    }

    pub fn proxy_type(&self) -> Option<ProxyType> {
        ProxyType::from_id(&self.type_)
    }

    /// 按类型剥掉不支持的字段（Kotlin `sanitizedForType`）。
    pub fn sanitized_for_type(&self) -> Self {
        let Some(t) = self.proxy_type() else {
            return self.clone();
        };
        Self {
            remote_port: if t.needs_remote_port() {
                self.remote_port.clone()
            } else {
                None
            },
            subdomain: if t.domain_based() {
                self.subdomain.clone()
            } else {
                None
            },
            custom_domains: if t.domain_based() {
                self.custom_domains.clone()
            } else {
                None
            },
            locations: if t.supports_http_advanced() {
                self.locations.clone()
            } else {
                None
            },
            http_user: if t.supports_http_auth() {
                self.http_user.clone()
            } else {
                None
            },
            http_password: if t.supports_http_auth() {
                self.http_password.clone()
            } else {
                None
            },
            host_header_rewrite: if t.supports_http_advanced() {
                self.host_header_rewrite.clone()
            } else {
                None
            },
            route_by_http_user: if t.supports_http_auth() {
                self.route_by_http_user.clone()
            } else {
                None
            },
            multiplexer: if t.supports_multiplexer() {
                self.multiplexer.clone()
            } else {
                None
            },
            secret_key: if t.needs_secret_key() {
                self.secret_key.clone()
            } else {
                None
            },
            allow_users: if t.needs_secret_key() {
                self.allow_users.clone()
            } else {
                None
            },
            plugin: self.plugin.as_ref().map(ProxyPluginConfig::sanitized),
            ..self.clone()
        }
    }
}

/// 代理级传输选项（Kotlin `ProxyTransportConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProxyTransportConfig {
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "bandwidthLimit"
    )]
    pub bandwidth_limit: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "bandwidthLimitMode"
    )]
    pub bandwidth_limit_mode: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "useEncryption"
    )]
    pub use_encryption: Option<bool>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "useCompression"
    )]
    pub use_compression: Option<bool>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "proxyProtocolVersion"
    )]
    pub proxy_protocol_version: Option<String>,
}

/// 代理插件（Kotlin `ProxyPluginConfig`）；未建模的插件字段在导入时原样保留于
/// TOML 文档层、但不可编辑（与 Kotlin 行为一致）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProxyPluginConfig {
    #[serde(rename = "type")]
    pub type_: String,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "localAddr")]
    pub local_addr: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "crtPath")]
    pub crt_path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "keyPath")]
    pub key_path: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "hostHeaderRewrite"
    )]
    pub host_header_rewrite: Option<String>,
}

impl ProxyPluginConfig {
    pub fn sanitized(&self) -> Self {
        Self {
            type_: self.type_.trim().to_string(),
            local_addr: self.local_addr.as_deref().and_then(some_if_non_blank),
            crt_path: self.crt_path.as_deref().and_then(some_if_non_blank),
            key_path: self.key_path.as_deref().and_then(some_if_non_blank),
            host_header_rewrite: self
                .host_header_rewrite
                .as_deref()
                .and_then(some_if_non_blank),
        }
    }

    pub fn is_https_plugin(&self) -> bool {
        self.type_ == PLUGIN_HTTPS2HTTP || self.type_ == PLUGIN_TLS2RAW
    }
}

/// 访问者（Kotlin `VisitorConfig`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VisitorConfig {
    pub name: String,
    #[serde(rename = "type")]
    pub type_: String,
    #[serde(default, rename = "serverName")]
    pub server_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "secretKey")]
    pub secret_key: Option<String>,
    #[serde(default = "default_bind_addr", rename = "bindAddr")]
    pub bind_addr: String,
    #[serde(default = "default_bind_port", rename = "bindPort")]
    pub bind_port: i32,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "serverUser"
    )]
    pub server_user: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "fallbackTo"
    )]
    pub fallback_to: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "fallbackTimeoutMs"
    )]
    pub fallback_timeout_ms: Option<i32>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "keepTunnelOpen"
    )]
    pub keep_tunnel_open: Option<bool>,
}

pub(crate) fn default_bind_addr() -> String {
    "127.0.0.1".into()
}
pub(crate) fn default_bind_port() -> i32 {
    6000
}

impl VisitorConfig {
    /// 测试辅助：空访问者（仅含模型默认值）。
    pub fn bare() -> Self {
        Self {
            name: String::new(),
            type_: String::new(),
            server_name: String::new(),
            secret_key: None,
            bind_addr: default_bind_addr(),
            bind_port: default_bind_port(),
            server_user: None,
            enabled: None,
            fallback_to: None,
            fallback_timeout_ms: None,
            keep_tunnel_open: None,
        }
    }

    pub fn is_xtcp(&self) -> bool {
        self.type_ == "xtcp"
    }

    /// 非 xtcp 访问者剥掉 xtcp 专属字段（Kotlin `sanitizedForType`）。
    pub fn sanitized_for_type(&self) -> Self {
        if self.is_xtcp() {
            self.clone()
        } else {
            Self {
                keep_tunnel_open: None,
                fallback_to: None,
                fallback_timeout_ms: None,
                ..self.clone()
            }
        }
    }
}

/// 访问端要连的服务端代理名：`{serverUser ?? user}.{serverName}`（Kotlin `FrpcNaming`）。
pub fn target_server_proxy_name(
    local_user: Option<&str>,
    server_user: Option<&str>,
    server_name: &str,
) -> String {
    let prefix = server_user
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .or_else(|| local_user.map(str::trim).filter(|s| !s.is_empty()));
    match prefix {
        Some(p) => format!("{p}.{server_name}"),
        None => server_name.to_string(),
    }
}

/// 拆 `user.name` 形式的服务端代理名；无前缀或残缺时返回 `None`。
pub fn split_prefixed_name(server_name: &str) -> Option<(String, String)> {
    let name = server_name.trim();
    let dot = name.find('.')?;
    if dot == 0 || dot >= name.len() - 1 {
        return None;
    }
    Some((name[..dot].to_string(), name[dot + 1..].to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 对拍 Kotlin `FrpcNamingTest`。
    mod naming {
        use super::*;

        #[test]
        fn matches_frp_semantics() {
            assert_eq!(
                target_server_proxy_name(Some("alice"), None, "test"),
                "alice.test"
            );
            assert_eq!(
                target_server_proxy_name(Some("alice"), Some("bob"), "test"),
                "bob.test"
            );
            assert_eq!(target_server_proxy_name(None, None, "test"), "test");
        }

        #[test]
        fn real_world_mixup() {
            assert_eq!(
                target_server_proxy_name(Some("小米14"), None, "ThinkBook16+.testx"),
                "小米14.ThinkBook16+.testx"
            );
            assert_eq!(
                target_server_proxy_name(Some("小米14"), Some("ThinkBook16+"), "testx"),
                "ThinkBook16+.testx"
            );
        }

        #[test]
        fn split_prefixed_name_gives_fix_suggestion() {
            assert_eq!(
                split_prefixed_name("ThinkBook16+.testx"),
                Some(("ThinkBook16+".into(), "testx".into()))
            );
            assert_eq!(split_prefixed_name("testx"), None);
            assert_eq!(split_prefixed_name(".testx"), None);
            assert_eq!(split_prefixed_name("thinkbook."), None);
        }
    }

    /// 对拍 Kotlin `ProxyTypeFieldTest` 的 sanitize 部分。
    mod sanitized_for_type {
        use super::*;

        #[test]
        fn strips_unsupported_fields() {
            let dirty = ProxyConfig {
                name: "p".into(),
                type_: "sudp".into(),
                local_ip: Some("127.0.0.1".into()),
                local_port: Some(53),
                remote_port: Some(6001),
                subdomain: Some("web".into()),
                custom_domains: Some(vec!["a.com".into()]),
                locations: Some(vec!["/".into()]),
                http_user: Some("u".into()),
                http_password: Some("p".into()),
                host_header_rewrite: Some("h".into()),
                multiplexer: Some("httpconnect".into()),
                ..ProxyConfig::bare()
            };
            let clean = dirty.sanitized_for_type();
            assert_eq!(clean.remote_port, None);
            assert_eq!(clean.subdomain, None);
            assert_eq!(clean.custom_domains, None);
            assert_eq!(clean.locations, None);
            assert_eq!(clean.http_user, None);
            assert_eq!(clean.multiplexer, None);
            assert_eq!(clean.type_, "sudp");
            assert_eq!(clean.local_port, Some(53));
        }

        #[test]
        fn strips_fallback_from_non_xtcp_visitor() {
            let xtcp = VisitorConfig {
                name: "x".into(),
                type_: "xtcp".into(),
                server_name: "p".into(),
                secret_key: Some("k".into()),
                fallback_to: Some("x-stcp".into()),
                fallback_timeout_ms: Some(2000),
                ..VisitorConfig::bare()
            };
            assert_eq!(xtcp.sanitized_for_type().fallback_to, Some("x-stcp".into()));
            assert_eq!(xtcp.sanitized_for_type().fallback_timeout_ms, Some(2000));

            let stcp = VisitorConfig {
                type_: "stcp".into(),
                ..xtcp.clone()
            }
            .sanitized_for_type();
            assert_eq!(stcp.fallback_to, None, "stcp 访问者必须剥掉 fallbackTo");
            assert_eq!(
                stcp.fallback_timeout_ms, None,
                "stcp 访问者必须剥掉 fallbackTimeoutMs"
            );
        }

        #[test]
        fn keeps_tcp_remote_port() {
            let proxy = ProxyConfig {
                name: "p".into(),
                type_: "tcp".into(),
                local_ip: Some("127.0.0.1".into()),
                local_port: Some(22),
                remote_port: Some(6001),
                ..ProxyConfig::bare()
            };
            assert_eq!(proxy.sanitized_for_type().remote_port, Some(6001));
        }
    }
}
