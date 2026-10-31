//! `frpc.toml` 编解码。
//!
//! Kotlin 来源：`shared/src/commonMain/.../toml/FrpcToml.kt`（ktoml 实现）。
//!
//! 与 Kotlin 实现的行为差异（有意为之）：
//! - 解码基于 `toml_edit`，**容忍未知字段**（官方全量示例中的 healthCheck、
//!   loadBalancer、annotations 等一律忽略），并支持官方示例的点号键写法
//!   （`log.to`、`transport.tls.enable`）；
//! - Kotlin 版会手工合并重复表头；`toml_edit` 遵循 TOML 规范直接报错，
//!   重复表头视为非法输入。
//!
//! 编码布局与 ktoml 一致：根标量在前，随后 `[log]` `[auth]` `[webServer]`
//! `[transport]`（含 `[transport.tls]`），最后 `[[proxies]]` `[[visitors]]`。

use std::collections::BTreeMap;

use toml_edit::{
    value, Array, ArrayOfTables, DocumentMut, InlineTable, Item, Table, TableLike, Value,
};

use crate::model::*;

/// 解码错误（Kotlin 版直接抛异常，这里显式返回）。
#[derive(Debug, thiserror::Error)]
pub enum TomlError {
    #[error("TOML 解析失败: {0}")]
    Parse(String),
    #[error("数值超出 i32 范围: {field} = {value}")]
    BadNumber { field: String, value: i64 },
}

pub struct FrpcToml;

// ---------- 解码辅助（直接作用于 &dyn TableLike，天然覆盖点号键与内联表） ----------

fn tsub<'a>(t: &'a dyn TableLike, k: &str) -> Option<&'a dyn TableLike> {
    t.get(k).and_then(Item::as_table_like)
}

fn tstr(t: &dyn TableLike, k: &str) -> Option<String> {
    t.get(k).and_then(Item::as_str).map(str::to_string)
}

fn tbool(t: &dyn TableLike, k: &str) -> Option<bool> {
    t.get(k).and_then(Item::as_bool)
}

fn tint(t: &dyn TableLike, k: &str) -> Result<Option<i32>, TomlError> {
    match t.get(k).map(Item::as_integer) {
        None | Some(None) => Ok(None),
        Some(Some(v)) => i32::try_from(v)
            .map(Some)
            .map_err(|_| TomlError::BadNumber {
                field: k.to_string(),
                value: v,
            }),
    }
}

fn tstrs(t: &dyn TableLike, k: &str) -> Option<Vec<String>> {
    t.get(k).and_then(Item::as_array).map(|a| {
        a.iter()
            .filter_map(|v| v.as_str().map(str::to_string))
            .collect()
    })
}

impl FrpcToml {
    /// 编码为 `frpc.toml`（Kotlin `FrpcToml.encode`：先做与 Kotlin 一致的规范化）。
    pub fn encode(config: &FrpcConfig) -> String {
        let normalized = FrpcConfig {
            user: opt_trim(&config.user),
            client_id: opt_trim(&config.client_id),
            auth: config.auth.as_ref().and_then(|a| {
                if a.method.trim().is_empty() || a.method == "none" {
                    None
                } else {
                    Some(a.clone())
                }
            }),
            metadatas: config.metadatas.as_ref().filter(|m| !m.is_empty()).cloned(),
            proxies: config
                .proxies
                .iter()
                .map(|p| p.sanitized_for_type())
                .collect(),
            visitors: config
                .visitors
                .iter()
                .map(|v| v.sanitized_for_type())
                .collect(),
            ..config.clone()
        };
        let c = &normalized;

        let mut doc = DocumentMut::new();
        doc.insert("serverAddr", value(c.server_addr.clone()));
        doc.insert("serverPort", value(i64::from(c.server_port)));
        if let Some(u) = &c.user {
            doc.insert("user", value(u.clone()));
        }
        if let Some(v) = &c.client_id {
            doc.insert("clientID", value(v.clone()));
        }
        doc.insert("loginFailExit", value(c.login_fail_exit));
        if let Some(v) = &c.nat_hole_stun_server {
            doc.insert("natHoleStunServer", value(v.clone()));
        }

        if let Some(log) = &c.log {
            let mut t = Table::new();
            // Kotlin 语义：to 为空串（默认值）时不写 to 字段 = frpc 默认输出到控制台
            if !log.to.is_empty() {
                t.insert("to", value(log.to.clone()));
            }
            t.insert("level", value(log.level.clone()));
            t.insert("maxDays", value(i64::from(log.max_days)));
            if let Some(d) = log.disable_print_color {
                t.insert("disablePrintColor", value(d));
            }
            doc.insert("log", Item::Table(t));
        }

        // Kotlin 语义：method 为 none/空白时不写 auth 段（frps 未启用认证时的合法形态）
        let write_auth = c
            .auth
            .as_ref()
            .map(|a| !a.method.trim().is_empty() && a.method != "none")
            .unwrap_or(false);
        if write_auth {
            let auth = c.auth.as_ref().expect("checked above");
            let mut t = Table::new();
            t.insert("method", value(auth.method.clone()));
            t.insert("token", value(auth.token.clone()));
            doc.insert("auth", Item::Table(t));
        }

        if let Some(ws) = &c.web_server {
            let mut t = Table::new();
            t.insert("addr", value(ws.addr.clone()));
            t.insert("port", value(i64::from(ws.port)));
            t.insert("user", value(ws.user.clone()));
            t.insert("password", value(ws.password.clone()));
            t.insert("pprofEnable", value(ws.pprof_enable));
            doc.insert("webServer", Item::Table(t));
        }

        if let Some(tr) = &c.transport {
            let mut t = transport_table(tr);
            doc.insert("transport", Item::Table(t));
        }

        if let Some(v) = c.udp_packet_size {
            doc.insert("udpPacketSize", value(i64::from(v)));
        }

        if let Some(m) = &c.metadatas {
            let mut it = InlineTable::new();
            for (k, v) in m {
                it.insert(k, Value::from(v.clone()));
            }
            doc.insert("metadatas", Item::Value(Value::InlineTable(it)));
        }

        if !c.proxies.is_empty() {
            let mut aot = ArrayOfTables::new();
            for p in &c.proxies {
                aot.push(proxy_table(p));
            }
            doc.insert("proxies", Item::ArrayOfTables(aot));
        }

        if !c.visitors.is_empty() {
            let mut aot = ArrayOfTables::new();
            for v in &c.visitors {
                aot.push(visitor_table(v));
            }
            doc.insert("visitors", Item::ArrayOfTables(aot));
        }

        doc.to_string()
    }

    /// 解码 `frpc.toml`（Kotlin `FrpcToml.decode`：未知字段忽略，随后规范化）。
    pub fn decode(text: &str) -> Result<FrpcConfig, TomlError> {
        let doc = text
            .parse::<DocumentMut>()
            .map_err(|e| TomlError::Parse(e.to_string()))?;
        let root = doc.as_table();

        let mut proxies = Vec::new();
        if let Some(aot) = root.get("proxies").and_then(Item::as_array_of_tables) {
            for el in aot.iter() {
                let plugin = tsub(el, "plugin").map(|pl| ProxyPluginConfig {
                    type_: tstr(pl, "type").unwrap_or_default(),
                    local_addr: tstr(pl, "localAddr"),
                    crt_path: tstr(pl, "crtPath"),
                    key_path: tstr(pl, "keyPath"),
                    host_header_rewrite: tstr(pl, "hostHeaderRewrite"),
                });
                let transport = tsub(el, "transport").map(|tr| ProxyTransportConfig {
                    bandwidth_limit: tstr(tr, "bandwidthLimit"),
                    bandwidth_limit_mode: tstr(tr, "bandwidthLimitMode"),
                    use_encryption: tbool(tr, "useEncryption"),
                    use_compression: tbool(tr, "useCompression"),
                    proxy_protocol_version: tstr(tr, "proxyProtocolVersion"),
                });
                proxies.push(ProxyConfig {
                    name: tstr(el, "name").unwrap_or_default(),
                    type_: tstr(el, "type").unwrap_or_default(),
                    enabled: tbool(el, "enabled"),
                    local_ip: tstr(el, "localIP"),
                    local_port: tint(el, "localPort")?,
                    remote_port: tint(el, "remotePort")?,
                    subdomain: tstr(el, "subdomain"),
                    custom_domains: tstrs(el, "customDomains"),
                    locations: tstrs(el, "locations"),
                    http_user: tstr(el, "httpUser"),
                    http_password: tstr(el, "httpPassword"),
                    host_header_rewrite: tstr(el, "hostHeaderRewrite"),
                    route_by_http_user: tstr(el, "routeByHTTPUser"),
                    multiplexer: tstr(el, "multiplexer"),
                    secret_key: tstr(el, "secretKey"),
                    allow_users: tstrs(el, "allowUsers"),
                    transport,
                    plugin,
                });
            }
        }

        let mut visitors = Vec::new();
        if let Some(aot) = root.get("visitors").and_then(Item::as_array_of_tables) {
            for el in aot.iter() {
                visitors.push(VisitorConfig {
                    name: tstr(el, "name").unwrap_or_default(),
                    type_: tstr(el, "type").unwrap_or_default(),
                    server_name: tstr(el, "serverName").unwrap_or_default(),
                    secret_key: tstr(el, "secretKey"),
                    bind_addr: tstr(el, "bindAddr").unwrap_or_else(default_bind_addr),
                    bind_port: tint(el, "bindPort")?.unwrap_or_else(default_bind_port),
                    server_user: tstr(el, "serverUser"),
                    enabled: tbool(el, "enabled"),
                    fallback_to: tstr(el, "fallbackTo"),
                    fallback_timeout_ms: tint(el, "fallbackTimeoutMs")?,
                    keep_tunnel_open: tbool(el, "keepTunnelOpen"),
                });
            }
        }

        let log = match tsub(root, "log") {
            Some(t) => Some(LogConfig {
                to: tstr(t, "to").unwrap_or_else(default_log_to),
                level: tstr(t, "level").unwrap_or_else(default_log_level),
                max_days: tint(t, "maxDays")?.unwrap_or_else(default_log_max_days),
                disable_print_color: tbool(t, "disablePrintColor"),
            }),
            None => None,
        };

        // Kotlin 解码行为：缺 [auth] 时补 `AuthConfig(method = "none")`；
        // [auth] 内缺 method 时补模型默认 "token"。
        let auth = match tsub(root, "auth") {
            Some(t) => Some(AuthConfig {
                method: tstr(t, "method").unwrap_or_else(default_auth_method),
                token: tstr(t, "token").unwrap_or_default(),
            }),
            None => Some(AuthConfig {
                method: "none".into(),
                token: String::new(),
            }),
        };

        let web_server = match tsub(root, "webServer") {
            Some(t) => Some(WebServerConfig {
                addr: tstr(t, "addr").unwrap_or_else(default_ws_addr),
                port: tint(t, "port")?.unwrap_or_else(default_ws_port),
                user: tstr(t, "user").unwrap_or_else(default_ws_user),
                password: tstr(t, "password").unwrap_or_else(default_ws_password),
                pprof_enable: tbool(t, "pprofEnable").unwrap_or(false),
            }),
            None => Some(WebServerConfig::default()),
        };

        let transport = match tsub(root, "transport") {
            Some(t) => {
                let tls = tsub(t, "tls").map(|tls| TlsConfig {
                    enable: tbool(tls, "enable").unwrap_or(true),
                    server_name: tstr(tls, "serverName"),
                    cert_file: tstr(tls, "certFile"),
                    key_file: tstr(tls, "keyFile"),
                    trusted_ca_file: tstr(tls, "trustedCaFile"),
                    disable_custom_tls_first_byte: tbool(tls, "disableCustomTLSFirstByte"),
                });
                Some(TransportConfig {
                    protocol: tstr(t, "protocol").unwrap_or_else(default_tr_protocol),
                    tcp_mux: tbool(t, "tcpMux").unwrap_or(true),
                    pool_count: tint(t, "poolCount")?.unwrap_or(0),
                    tls,
                    dial_server_timeout: tint(t, "dialServerTimeout")?
                        .unwrap_or_else(default_dial_timeout),
                    dial_server_keepalive: tint(t, "dialServerKeepalive")?
                        .unwrap_or_else(default_dial_keepalive),
                    connect_server_local_ip: tstr(t, "connectServerLocalIP"),
                    proxy_url: tstr(t, "proxyURL"),
                    heartbeat_interval: tint(t, "heartbeatInterval")?,
                    heartbeat_timeout: tint(t, "heartbeatTimeout")?,
                    tcp_mux_keepalive_interval: tint(t, "tcpMuxKeepaliveInterval")?,
                })
            }
            None => None,
        };

        let metadatas = tsub(root, "metadatas").map(|t| {
            t.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.to_string(), s.to_string())))
                .collect::<BTreeMap<String, String>>()
        });

        Ok(FrpcConfig {
            server_addr: tstr(root, "serverAddr").unwrap_or_default(),
            server_port: tint(root, "serverPort")?.unwrap_or(7000),
            client_id: tstr(root, "clientID"),
            user: tstr(root, "user"),
            login_fail_exit: tbool(root, "loginFailExit").unwrap_or(false),
            nat_hole_stun_server: tstr(root, "natHoleStunServer"),
            log,
            auth,
            web_server,
            transport,
            udp_packet_size: tint(root, "udpPacketSize")?,
            metadatas,
            // Kotlin 解码最后一步：对 proxies / visitors 做按类型清洗
            proxies: proxies.iter().map(|p| p.sanitized_for_type()).collect(),
            visitors: visitors.iter().map(|v| v.sanitized_for_type()).collect(),
        })
    }
}

fn transport_table(tr: &TransportConfig) -> Table {
    let mut t = Table::new();
    t.insert("protocol", value(tr.protocol.clone()));
    t.insert("tcpMux", value(tr.tcp_mux));
    t.insert("poolCount", value(i64::from(tr.pool_count)));
    t.insert(
        "dialServerTimeout",
        value(i64::from(tr.dial_server_timeout)),
    );
    t.insert(
        "dialServerKeepalive",
        value(i64::from(tr.dial_server_keepalive)),
    );
    if let Some(v) = &tr.connect_server_local_ip {
        t.insert("connectServerLocalIP", value(v.clone()));
    }
    if let Some(v) = &tr.proxy_url {
        t.insert("proxyURL", value(v.clone()));
    }
    if let Some(v) = tr.heartbeat_interval {
        t.insert("heartbeatInterval", value(i64::from(v)));
    }
    if let Some(v) = tr.heartbeat_timeout {
        t.insert("heartbeatTimeout", value(i64::from(v)));
    }
    if let Some(v) = tr.tcp_mux_keepalive_interval {
        t.insert("tcpMuxKeepaliveInterval", value(i64::from(v)));
    }
    if let Some(tls) = &tr.tls {
        let mut tt = Table::new();
        tt.insert("enable", value(tls.enable));
        if let Some(v) = &tls.server_name {
            tt.insert("serverName", value(v.clone()));
        }
        if let Some(v) = &tls.cert_file {
            tt.insert("certFile", value(v.clone()));
        }
        if let Some(v) = &tls.key_file {
            tt.insert("keyFile", value(v.clone()));
        }
        if let Some(v) = &tls.trusted_ca_file {
            tt.insert("trustedCaFile", value(v.clone()));
        }
        if let Some(v) = tls.disable_custom_tls_first_byte {
            tt.insert("disableCustomTLSFirstByte", value(v));
        }
        t.insert("tls", Item::Table(tt));
    }
    t
}

fn proxy_table(p: &ProxyConfig) -> Table {
    let mut t = Table::new();
    t.insert("name", value(p.name.clone()));
    t.insert("type", value(p.type_.clone()));
    if let Some(v) = p.enabled {
        t.insert("enabled", value(v));
    }
    if let Some(v) = &p.local_ip {
        t.insert("localIP", value(v.clone()));
    }
    if let Some(v) = p.local_port {
        t.insert("localPort", value(i64::from(v)));
    }
    if let Some(v) = p.remote_port {
        t.insert("remotePort", value(i64::from(v)));
    }
    if let Some(v) = &p.subdomain {
        t.insert("subdomain", value(v.clone()));
    }
    if let Some(v) = &p.custom_domains {
        t.insert("customDomains", str_array(v));
    }
    if let Some(v) = &p.locations {
        t.insert("locations", str_array(v));
    }
    if let Some(v) = &p.http_user {
        t.insert("httpUser", value(v.clone()));
    }
    if let Some(v) = &p.http_password {
        t.insert("httpPassword", value(v.clone()));
    }
    if let Some(v) = &p.host_header_rewrite {
        t.insert("hostHeaderRewrite", value(v.clone()));
    }
    if let Some(v) = &p.route_by_http_user {
        t.insert("routeByHTTPUser", value(v.clone()));
    }
    if let Some(v) = &p.multiplexer {
        t.insert("multiplexer", value(v.clone()));
    }
    if let Some(v) = &p.secret_key {
        t.insert("secretKey", value(v.clone()));
    }
    if let Some(v) = &p.allow_users {
        t.insert("allowUsers", str_array(v));
    }
    if let Some(tr) = &p.transport {
        let mut tt = Table::new();
        if let Some(v) = &tr.bandwidth_limit {
            tt.insert("bandwidthLimit", value(v.clone()));
        }
        if let Some(v) = &tr.bandwidth_limit_mode {
            tt.insert("bandwidthLimitMode", value(v.clone()));
        }
        if let Some(v) = tr.use_encryption {
            tt.insert("useEncryption", value(v));
        }
        if let Some(v) = tr.use_compression {
            tt.insert("useCompression", value(v));
        }
        if let Some(v) = &tr.proxy_protocol_version {
            tt.insert("proxyProtocolVersion", value(v.clone()));
        }
        t.insert("transport", Item::Table(tt));
    }
    if let Some(pl) = &p.plugin {
        let mut pt = Table::new();
        pt.insert("type", value(pl.type_.clone()));
        if let Some(v) = &pl.local_addr {
            pt.insert("localAddr", value(v.clone()));
        }
        if let Some(v) = &pl.crt_path {
            pt.insert("crtPath", value(v.clone()));
        }
        if let Some(v) = &pl.key_path {
            pt.insert("keyPath", value(v.clone()));
        }
        if let Some(v) = &pl.host_header_rewrite {
            pt.insert("hostHeaderRewrite", value(v.clone()));
        }
        t.insert("plugin", Item::Table(pt));
    }
    t
}

fn visitor_table(v: &VisitorConfig) -> Table {
    let mut t = Table::new();
    t.insert("name", value(v.name.clone()));
    t.insert("type", value(v.type_.clone()));
    t.insert("serverName", value(v.server_name.clone()));
    if let Some(s) = &v.secret_key {
        t.insert("secretKey", value(s.clone()));
    }
    t.insert("bindAddr", value(v.bind_addr.clone()));
    t.insert("bindPort", value(i64::from(v.bind_port)));
    if let Some(s) = &v.server_user {
        t.insert("serverUser", value(s.clone()));
    }
    if let Some(b) = v.enabled {
        t.insert("enabled", value(b));
    }
    if let Some(s) = &v.fallback_to {
        t.insert("fallbackTo", value(s.clone()));
    }
    if let Some(n) = v.fallback_timeout_ms {
        t.insert("fallbackTimeoutMs", value(i64::from(n)));
    }
    if let Some(b) = v.keep_tunnel_open {
        t.insert("keepTunnelOpen", value(b));
    }
    t
}

fn opt_trim(v: &Option<String>) -> Option<String> {
    v.as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn str_array(items: &[String]) -> Item {
    let mut a = Array::new();
    for s in items {
        a.push(s.clone());
    }
    value(a)
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---------- 对拍 Kotlin FrpcTomlTest ----------

    #[test]
    fn encode_omits_remote_port_when_empty() {
        let config = FrpcConfig {
            server_addr: "frps.example.com".into(),
            proxies: vec![ProxyConfig {
                name: "web".into(),
                type_: "tcp".into(),
                local_ip: Some("127.0.0.1".into()),
                local_port: Some(8080),
                ..ProxyConfig::bare()
            }],
            ..FrpcConfig::default()
        };
        let toml = FrpcToml::encode(&config);
        assert!(
            !toml.contains("remotePort"),
            "留空时不应写出 remotePort：\n{toml}"
        );
    }

    #[test]
    fn round_trip_full_config() {
        let config = FrpcConfig {
            server_addr: "frps.example.com".into(),
            server_port: 7000,
            user: Some("alice".into()),
            client_id: Some("phone-01".into()),
            login_fail_exit: true,
            log: Some(LogConfig {
                to: "console".into(),
                level: "debug".into(),
                max_days: 3,
                disable_print_color: None,
            }),
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "secret-token".into(),
            }),
            web_server: Some(WebServerConfig {
                addr: "127.0.0.1".into(),
                port: 7400,
                user: "admin".into(),
                password: "admin".into(),
                pprof_enable: false,
            }),
            transport: Some(TransportConfig {
                protocol: "tcp".into(),
                tcp_mux: true,
                pool_count: 2,
                tls: Some(TlsConfig {
                    enable: true,
                    server_name: Some("frps.example.com".into()),
                    ..TlsConfig::default()
                }),
                connect_server_local_ip: None,
                proxy_url: None,
                ..TransportConfig::default()
            }),
            udp_packet_size: Some(1500),
            proxies: vec![
                ProxyConfig {
                    name: "ssh".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    remote_port: Some(6001),
                    transport: Some(ProxyTransportConfig {
                        bandwidth_limit: Some("1MB".into()),
                        use_encryption: Some(true),
                        use_compression: Some(false),
                        ..ProxyTransportConfig::default()
                    }),
                    ..ProxyConfig::bare()
                },
                ProxyConfig {
                    name: "web".into(),
                    type_: "http".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(80),
                    subdomain: Some("web01".into()),
                    custom_domains: Some(vec!["web01.example.com".into()]),
                    locations: Some(vec!["/".into(), "/pic".into()]),
                    http_user: Some("u".into()),
                    http_password: Some("p".into()),
                    host_header_rewrite: Some("example.com".into()),
                    ..ProxyConfig::bare()
                },
                ProxyConfig {
                    name: "dns".into(),
                    type_: "udp".into(),
                    local_ip: Some("192.168.1.1".into()),
                    local_port: Some(53),
                    remote_port: Some(6002),
                    ..ProxyConfig::bare()
                },
                ProxyConfig {
                    name: "secret_tcp".into(),
                    type_: "stcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    secret_key: Some("abcdefg".into()),
                    allow_users: Some(vec!["*".into()]),
                    ..ProxyConfig::bare()
                },
                ProxyConfig {
                    name: "disabled_ssh".into(),
                    type_: "tcp".into(),
                    local_ip: Some("127.0.0.1".into()),
                    local_port: Some(22),
                    remote_port: Some(6003),
                    enabled: Some(false),
                    ..ProxyConfig::bare()
                },
            ],
            visitors: vec![VisitorConfig {
                name: "secret_tcp_visitor".into(),
                type_: "stcp".into(),
                server_name: "secret_tcp".into(),
                secret_key: Some("abcdefg".into()),
                bind_addr: "127.0.0.1".into(),
                bind_port: 9000,
                ..VisitorConfig::bare()
            }],
            ..FrpcConfig::default()
        };

        let toml = FrpcToml::encode(&config);
        let decoded = FrpcToml::decode(&toml).expect("decode");
        if config != decoded {
            panic!(
                "round trip 不一致\n--- toml ---\n{toml}\n--- config ---\n{config:#?}\n--- decoded ---\n{decoded:#?}"
            );
        }
    }

    #[test]
    fn round_trip_minimal_config_omits_optional_sections() {
        let config = FrpcConfig {
            server_addr: "1.2.3.4".into(),
            server_port: 7000,
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "t".into(),
            }),
            web_server: None,
            transport: None,
            log: None,
            ..FrpcConfig::default()
        };
        let toml = FrpcToml::encode(&config);
        assert!(
            !toml.contains("webServer"),
            "webServer section should be omitted"
        );
        assert!(
            !toml.contains("transport"),
            "transport section should be omitted"
        );
        assert!(
            !toml.contains("[[proxies]]"),
            "no proxies should be emitted"
        );
        let decoded = FrpcToml::decode(&toml).expect("decode");
        assert_eq!(
            decoded,
            FrpcConfig {
                web_server: Some(WebServerConfig::default()),
                ..config.clone()
            }
        );
    }

    #[test]
    fn encode_contains_expected_keys() {
        let toml = FrpcToml::encode(&FrpcConfig {
            server_addr: "frps.example.com".into(),
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "tok".into(),
            }),
            web_server: Some(WebServerConfig {
                port: 7400,
                ..WebServerConfig::default()
            }),
            proxies: vec![ProxyConfig {
                name: "ssh".into(),
                type_: "tcp".into(),
                local_ip: Some("127.0.0.1".into()),
                local_port: Some(22),
                remote_port: Some(6000),
                ..ProxyConfig::bare()
            }],
            ..FrpcConfig::default()
        });
        assert!(toml.contains("serverAddr = \"frps.example.com\""), "{toml}");
        assert!(toml.contains("serverPort = 7000"), "{toml}");
        assert!(toml.contains("[[proxies]]"), "{toml}");
        assert!(toml.contains("name = \"ssh\""), "{toml}");
        assert!(toml.contains("type = \"tcp\""), "{toml}");
        assert!(toml.contains("[webServer]"), "{toml}");
        assert!(toml.contains("port = 7400"), "{toml}");
    }

    #[test]
    fn visitor_xtcp_only_fields_sanitized_per_type() {
        let toml = r#"
            serverAddr = "127.0.0.1"
            [[visitors]]
            name = "v-stcp"
            type = "stcp"
            serverName = "p"
            secretKey = "k"
            bindAddr = "127.0.0.1"
            bindPort = 6000
            keepTunnelOpen = true
        "#;
        let stcp = FrpcToml::decode(toml).expect("decode");
        assert_eq!(stcp.visitors.first().unwrap().keep_tunnel_open, None);
        let stcp_toml = FrpcToml::encode(&stcp);
        assert!(!stcp_toml.contains("keepTunnelOpen"), "{stcp_toml}");

        let xtcp = FrpcConfig {
            visitors: stcp
                .visitors
                .iter()
                .map(|v| VisitorConfig {
                    type_: "xtcp".into(),
                    keep_tunnel_open: Some(true),
                    ..v.clone()
                })
                .collect(),
            ..stcp.clone()
        };
        let xtcp_toml = FrpcToml::encode(&xtcp);
        assert!(xtcp_toml.contains("keepTunnelOpen = true"), "{xtcp_toml}");
        assert_eq!(
            FrpcToml::decode(&xtcp_toml)
                .expect("decode")
                .visitors
                .first()
                .unwrap()
                .keep_tunnel_open,
            Some(true)
        );
    }

    #[test]
    fn visitor_enabled_round_trip() {
        let toml = r#"
            serverAddr = "127.0.0.1"
            [[visitors]]
            name = "v-stcp"
            type = "stcp"
            serverName = "p"
            bindPort = 6000
            enabled = false
        "#;
        let decoded = FrpcToml::decode(toml).expect("decode");
        assert_eq!(decoded.visitors.first().unwrap().enabled, Some(false));

        let encoded = FrpcToml::encode(&decoded);
        assert!(encoded.contains("enabled = false"), "{encoded}");
        assert_eq!(
            FrpcToml::decode(&encoded)
                .expect("decode")
                .visitors
                .first()
                .unwrap()
                .enabled,
            Some(false)
        );

        let enabled = FrpcConfig {
            visitors: decoded
                .visitors
                .iter()
                .map(|v| VisitorConfig {
                    enabled: None,
                    ..v.clone()
                })
                .collect(),
            ..decoded.clone()
        };
        assert!(!FrpcToml::encode(&enabled).contains("enabled"));
    }

    // ---------- 对拍 Kotlin FrpcTomlAdvancedTest ----------

    #[test]
    fn round_trip_transport_heartbeat_and_tls_files() {
        let config = FrpcConfig {
            server_addr: "frps.example.com".into(),
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "t".into(),
            }),
            transport: Some(TransportConfig {
                protocol: "tcp".into(),
                tcp_mux: true,
                pool_count: 2,
                dial_server_timeout: 10,
                dial_server_keepalive: -1,
                connect_server_local_ip: Some("192.168.1.10".into()),
                proxy_url: Some("socks5://127.0.0.1:1080".into()),
                heartbeat_interval: Some(15),
                heartbeat_timeout: Some(120),
                tcp_mux_keepalive_interval: Some(45),
                tls: Some(TlsConfig {
                    enable: true,
                    server_name: Some("frps.example.com".into()),
                    cert_file: Some("/etc/frp/client.crt".into()),
                    key_file: Some("/etc/frp/client.key".into()),
                    trusted_ca_file: Some("/etc/frp/ca.crt".into()),
                    disable_custom_tls_first_byte: Some(false),
                }),
            }),
            log: Some(LogConfig {
                to: "console".into(),
                level: "debug".into(),
                max_days: 7,
                disable_print_color: Some(true),
            }),
            metadatas: Some(BTreeMap::from([("token".into(), "meta-token".into())])),
            ..FrpcConfig::default()
        };

        let toml = FrpcToml::encode(&config);
        assert!(toml.contains("heartbeatInterval = 15"), "{toml}");
        assert!(toml.contains("tcpMuxKeepaliveInterval = 45"), "{toml}");
        assert!(toml.contains("certFile = "), "{toml}");
        assert!(toml.contains("disablePrintColor = true"), "{toml}");
        assert!(toml.contains("token = \"meta-token\""), "{toml}");

        assert_eq!(FrpcToml::decode(&toml).expect("decode"), config);
    }

    #[test]
    fn encode_omits_unset_optional_transport_fields() {
        let toml = FrpcToml::encode(&FrpcConfig {
            server_addr: "1.2.3.4".into(),
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "t".into(),
            }),
            transport: Some(TransportConfig {
                tls: Some(TlsConfig::default()),
                ..TransportConfig::default()
            }),
            metadatas: None,
            ..FrpcConfig::default()
        });
        assert!(!toml.contains("heartbeatInterval"), "{toml}");
        assert!(!toml.contains("tcpMuxKeepaliveInterval"), "{toml}");
        assert!(!toml.contains("disableCustomTLSFirstByte"), "{toml}");
        assert!(!toml.contains("metadatas"), "{toml}");
        assert!(!toml.contains("certFile"), "{toml}");
    }

    #[test]
    fn round_trip_https2http_plugin() {
        let config = FrpcConfig {
            server_addr: "frps.example.com".into(),
            auth: Some(AuthConfig {
                method: "token".into(),
                token: "t".into(),
            }),
            proxies: vec![ProxyConfig {
                name: "web-https".into(),
                type_: "https".into(),
                local_ip: Some("127.0.0.1".into()),
                local_port: Some(8080),
                custom_domains: Some(vec!["web.example.com".into()]),
                plugin: Some(ProxyPluginConfig {
                    type_: PLUGIN_HTTPS2HTTP.into(),
                    local_addr: Some("127.0.0.1:8080".into()),
                    crt_path: Some("/etc/frp/web.crt".into()),
                    key_path: Some("/etc/frp/web.key".into()),
                    host_header_rewrite: None,
                }),
                ..ProxyConfig::bare()
            }],
            ..FrpcConfig::default()
        };
        let toml = FrpcToml::encode(&config);
        assert!(toml.contains("plugin"), "{toml}");
        assert!(toml.contains("https2http"), "{toml}");
        assert_eq!(FrpcToml::decode(&toml).expect("decode"), config);
    }

    #[test]
    fn import_keeps_plugin_for_non_https_type() {
        let toml = r#"
            serverAddr = "127.0.0.1"
            [[proxies]]
            name = "socks"
            type = "tcp"
            localIP = "127.0.0.1"
            localPort = 1080
            remotePort = 6000
            [proxies.plugin]
            type = "socks5"
            username = "u"
            password = "p"
        "#;
        let decoded = FrpcToml::decode(toml).expect("decode");
        let plugin = decoded.proxies.first().unwrap().plugin.as_ref().unwrap();
        assert_eq!(plugin.type_, "socks5");
        assert!(FrpcToml::encode(&decoded).contains("socks5"));
    }

    #[test]
    fn auth_none_omits_section_entirely() {
        let toml = FrpcToml::encode(&FrpcConfig {
            server_addr: "127.0.0.1".into(),
            auth: Some(AuthConfig {
                method: "none".into(),
                token: String::new(),
            }),
            ..FrpcConfig::default()
        });
        assert!(!toml.contains("[auth]"), "{toml}");
        assert!(!toml.contains("method"), "{toml}");
        assert_eq!(
            FrpcToml::decode(&toml)
                .expect("decode")
                .auth
                .expect("auth")
                .method,
            "none"
        );
    }

    // ---------- 对拍 Kotlin ProxyTypeFieldTest 的解码部分 ----------

    #[test]
    fn decode_strips_invalid_fields_from_imported_toml() {
        let toml = r#"
            serverAddr = "1.2.3.4"
            serverPort = 7000

            [auth]
            method = "token"
            token = "t"

            [[proxies]]
            name = "bad-sudp"
            type = "sudp"
            localIP = "127.0.0.1"
            localPort = 53
            remotePort = 6001
        "#;
        let config = FrpcToml::decode(toml).expect("decode");
        let proxy = config.proxies.first().unwrap();
        assert_eq!(proxy.type_, "sudp");
        assert_eq!(
            proxy.remote_port, None,
            "导入时应清理 sudp 不支持的 remotePort"
        );
        let issues = crate::validate::ConfigValidator::validate(&config, |_| false);
        assert!(
            issues
                .iter()
                .all(|i| i.reason != crate::validate::Reason::FieldNotSupportedForType),
            "unexpected issues: {issues:?}"
        );
    }

    #[test]
    fn decode_tcpmux_remote_port_stripped() {
        let toml = r#"
            serverAddr = "1.2.3.4"

            [auth]
            token = "t"

            [[proxies]]
            name = "mux"
            type = "tcpmux"
            multiplexer = "httpconnect"
            localIP = "127.0.0.1"
            localPort = 10701
            customDomains = ["tunnel1"]
            remotePort = 6001
        "#;
        let proxy = FrpcToml::decode(toml)
            .expect("decode")
            .proxies
            .into_iter()
            .next()
            .unwrap();
        assert_eq!(proxy.remote_port, None);
        assert_eq!(proxy.multiplexer, Some("httpconnect".into()));
        assert_eq!(proxy.custom_domains, Some(vec!["tunnel1".into()]));
    }
}
