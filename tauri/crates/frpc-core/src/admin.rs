//! frpc admin API（webServer）客户端：真实代理 / 访问者状态、重载、停止、配置读取。
//!
//! Kotlin 来源：
//! - `shared/src/jvmCommonMain/.../admin/FrpcAdminClient.kt`
//! - `shared/src/commonMain/.../admin/AdminModels.kt`
//!
//! 对齐点：显式禁用系统代理（Kotlin `Proxy.NO_PROXY`）、Basic 认证、
//! 连接超时 3s / 读超时 5s、非预期状态码抛出带 httpCode 的异常。

use std::collections::BTreeMap;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine as _;
use reqwest::{Client, Method, StatusCode};
use serde::{Deserialize, Serialize};

/// 单条代理状态（frps admin API 返回）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyStatusItem {
    #[serde(default)]
    pub name: String,
    #[serde(default, rename = "type")]
    pub type_: String,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub err: String,
    #[serde(default, rename = "local_addr")]
    pub local_addr: String,
    #[serde(default)]
    pub plugin: String,
    #[serde(default, rename = "remote_addr")]
    pub remote_addr: String,
    #[serde(default)]
    pub today_traffic_in: u64,
    #[serde(default)]
    pub today_traffic_out: u64,
    #[serde(default)]
    pub cur_conns: i64,
}

/// 一次状态抓取快照。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyStatusSnapshot {
    pub by_type: BTreeMap<String, Vec<ProxyStatusItem>>,
    pub fetched_at_epoch_ms: i64,
}

impl ProxyStatusSnapshot {
    pub fn all(&self) -> Vec<&ProxyStatusItem> {
        self.by_type.values().flatten().collect()
    }

    pub fn item(&self, name: &str) -> Option<&ProxyStatusItem> {
        self.all().into_iter().find(|i| i.name == name)
    }

    pub fn empty(now_epoch_ms: i64) -> Self {
        Self {
            by_type: BTreeMap::new(),
            fetched_at_epoch_ms: now_epoch_ms,
        }
    }
}

/// admin API 健康状态。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum AdminHealth {
    Ok,
    Unreachable,
    AuthFailed { message: String },
}

/// admin API 错误（Kotlin `AdminApiException`）。
#[derive(Debug, thiserror::Error)]
#[error("{message}")]
pub struct AdminApiError {
    pub message: String,
    pub http_code: i32,
}

pub fn now_epoch_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// admin API 客户端。
#[derive(Clone)]
pub struct FrpcAdminClient {
    client: Client,
    base_url: String,
}

impl FrpcAdminClient {
    pub fn new(addr: &str, port: i32, user: Option<&str>, password: Option<&str>) -> Self {
        let mut builder = Client::builder()
            .connect_timeout(Duration::from_millis(3000))
            .read_timeout(Duration::from_millis(5000))
            .no_proxy();
        let mut headers = reqwest::header::HeaderMap::new();
        if let Some(u) = user.map(str::trim).filter(|s| !s.is_empty()) {
            let cred = format!("{u}:{}", password.unwrap_or(""));
            let encoded = base64::engine::general_purpose::STANDARD.encode(cred.as_bytes());
            if let Ok(v) = reqwest::header::HeaderValue::from_str(&format!("Basic {encoded}")) {
                headers.insert(reqwest::header::AUTHORIZATION, v);
            }
        }
        builder = builder.default_headers(headers);
        Self {
            client: builder.build().unwrap_or_else(|_| Client::new()),
            base_url: format!("http://{addr}:{port}"),
        }
    }

    async fn request(&self, method: Method, path: &str) -> Result<String, AdminApiError> {
        let url = format!("{}{path}", self.base_url);
        let resp = self
            .client
            .request(method.clone(), &url)
            .send()
            .await
            .map_err(|e| AdminApiError {
                message: format!("{method} {path} -> {e}"),
                http_code: -1,
            })?;
        let code = resp.status();
        if code != StatusCode::OK {
            let err_text = resp.text().await.unwrap_or_default();
            return Err(AdminApiError {
                message: format!(
                    "{method} {path} -> HTTP {} {}",
                    code.as_u16(),
                    &err_text[..err_text.len().min(200)]
                ),
                http_code: code.as_u16() as i32,
            });
        }
        resp.text().await.map_err(|e| AdminApiError {
            message: format!("{method} {path} -> {e}"),
            http_code: -1,
        })
    }

    /// 健康检查：200 → Ok，401 → AuthFailed，其余 → Unreachable。
    pub async fn healthz(&self) -> AdminHealth {
        match self.request(Method::GET, "/healthz").await {
            Ok(_) => AdminHealth::Ok,
            Err(e) if e.http_code == 401 => AdminHealth::AuthFailed { message: e.message },
            Err(_) => AdminHealth::Unreachable,
        }
    }

    /// 抓取全部代理 / 访问者状态。
    pub async fn status(&self) -> Result<ProxyStatusSnapshot, AdminApiError> {
        let body = self.request(Method::GET, "/api/status").await?;
        let by_type: BTreeMap<String, Vec<ProxyStatusItem>> =
            serde_json::from_str(&body).map_err(|e| AdminApiError {
                message: format!("status 解析失败: {e}"),
                http_code: -1,
            })?;
        Ok(ProxyStatusSnapshot {
            by_type,
            fetched_at_epoch_ms: now_epoch_ms(),
        })
    }

    pub async fn reload(&self) -> Result<bool, AdminApiError> {
        self.request(Method::GET, "/api/reload").await?;
        Ok(true)
    }

    pub async fn stop(&self) -> Result<bool, AdminApiError> {
        self.request(Method::POST, "/api/stop").await?;
        Ok(true)
    }

    pub async fn config_toml(&self) -> Result<String, AdminApiError> {
        self.request(Method::GET, "/api/config").await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    /// 极简 HTTP/1.1 测试服务器：按路径应答，记录最近一次请求的方法 / 路径 / Authorization。
    struct TestServer {
        port: u16,
        last: Arc<Mutex<(String, String, Option<String>)>>,
    }

    impl TestServer {
        async fn spawn(responses: Vec<(&'static str, u16, &'static str)>) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
            let port = listener.local_addr().unwrap().port();
            let last: Arc<Mutex<(String, String, Option<String>)>> =
                Arc::new(Mutex::new((String::new(), String::new(), None)));
            let res: Arc<Vec<(&'static str, u16, &'static str)>> =
                Arc::new(responses.into_iter().map(|(p, c, b)| (p, c, b)).collect());

            let last_cl = last.clone();
            tokio::spawn(async move {
                loop {
                    let Ok((mut sock, _)) = listener.accept().await else {
                        return;
                    };
                    let last = last_cl.clone();
                    let res = res.clone();
                    tokio::spawn(async move {
                        loop {
                            let mut buf = Vec::new();
                            let mut chunk = [0u8; 4096];
                            let header_end;
                            loop {
                                let Ok(n) = sock.read(&mut chunk).await else {
                                    return;
                                };
                                if n == 0 {
                                    return;
                                }
                                buf.extend_from_slice(&chunk[..n]);
                                if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                                    header_end = pos + 4;
                                    break;
                                }
                                if buf.len() > 64 * 1024 {
                                    return;
                                }
                            }
                            let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
                            let mut lines = head.lines();
                            let request_line = lines.next().unwrap_or("").to_string();
                            let mut parts = request_line.split_whitespace();
                            let method = parts.next().unwrap_or("").to_string();
                            let path = parts.next().unwrap_or("").to_string();
                            let auth = lines
                                .filter_map(|l| {
                                    let (k, v) = l.split_once(':')?;
                                    if k.trim().eq_ignore_ascii_case("authorization") {
                                        Some(v.trim().to_string())
                                    } else {
                                        None
                                    }
                                })
                                .next();

                            // 消费请求体（本客户端不发送 body，跳过）
                            {
                                let mut state = last.lock().unwrap();
                                *state = (method.clone(), path.clone(), auth.clone());
                            }

                            let hit = res
                                .iter()
                                .find(|(p, _, _)| path == *p || (path.starts_with(p) && *p != "/"));
                            let (found_path, code, body) = match hit {
                                Some((p, c, b)) => (*p, *c, *b),
                                None => ("", 404u16, ""),
                            };
                            let _ = found_path;
                            if code == 404 {
                                let resp =
                                    format!("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                                let _ = sock.write_all(resp.as_bytes()).await;
                                continue;
                            }
                            let resp = format!(
                                "HTTP/1.1 {code} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
                                body.len()
                            );
                            let _ = sock.write_all(resp.as_bytes()).await;
                            let _ = sock.flush().await;
                        }
                    });
                }
            });
            Self { port, last }
        }

        fn snapshot(&self) -> (String, String, Option<String>) {
            self.last.lock().unwrap().clone()
        }
    }

    fn client(port: u16) -> FrpcAdminClient {
        FrpcAdminClient::new("127.0.0.1", port as i32, None, None)
    }

    const STATUS_JSON: &str = r#"{"tcp":[{"name":"ssh","type":"tcp","status":"running","err":"","local_addr":"127.0.0.1:22","plugin":"","remote_addr":"1.2.3.4:6000"}],
         "http":[{"name":"web","type":"http","status":"error","err":"port already used","local_addr":"127.0.0.1:80","plugin":"","remote_addr":""}]}"#;

    #[tokio::test]
    async fn status_parses_proxy_map() {
        let server = TestServer::spawn(vec![("/api/status", 200, STATUS_JSON)]).await;
        let snapshot = client(server.port).status().await.expect("status");
        assert_eq!(2, snapshot.all().len());
        assert_eq!("running", snapshot.item("ssh").unwrap().status);
        assert_eq!("1.2.3.4:6000", snapshot.item("ssh").unwrap().remote_addr);
        assert_eq!("error", snapshot.item("web").unwrap().status);
        assert!(snapshot
            .item("web")
            .unwrap()
            .err
            .contains("port already used"));
        assert_eq!(
            vec!["ssh"],
            snapshot.by_type["tcp"]
                .iter()
                .map(|i| i.name.clone())
                .collect::<Vec<_>>()
        );
        let (method, path, _) = server.snapshot();
        assert_eq!("GET", method);
        assert_eq!("/api/status", path);
    }

    #[tokio::test]
    async fn healthz_ok() {
        let server = TestServer::spawn(vec![("/healthz", 200, "ok")]).await;
        assert_eq!(AdminHealth::Ok, client(server.port).healthz().await);
    }

    #[tokio::test]
    async fn healthz_auth_failed() {
        // 认证型测试服务器：无 Authorization 头 → 401，带 → 200
        let auth_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let auth_port = auth_listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = auth_listener.accept().await else {
                    return;
                };
                let mut buf = [0u8; 4096];
                let Ok(n) = sock.read(&mut buf).await else {
                    return;
                };
                let head = String::from_utf8_lossy(&buf[..n]).to_string();
                if head.to_ascii_lowercase().contains("authorization:") {
                    let _ = sock
                        .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
                        .await;
                } else {
                    let _ = sock
                        .write_all(
                            b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 12\r\n\r\nunauthorized",
                        )
                        .await;
                }
            }
        });
        let anonymous = FrpcAdminClient::new("127.0.0.1", auth_port as i32, None, None);
        let result = anonymous.healthz().await;
        assert!(
            matches!(result, AdminHealth::AuthFailed { .. }),
            "expected AuthFailed but was {result:?}"
        );
        if let AdminHealth::AuthFailed { message } = result {
            assert!(message.contains("401"), "{message}");
        }
        let authenticated =
            FrpcAdminClient::new("127.0.0.1", auth_port as i32, Some("admin"), Some("admin"));
        assert_eq!(AdminHealth::Ok, authenticated.healthz().await);
    }

    #[tokio::test]
    async fn healthz_unreachable_on_closed_port() {
        let probe = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let free_port = probe.local_addr().unwrap().port();
        drop(probe);
        assert_eq!(AdminHealth::Unreachable, client(free_port).healthz().await);
    }

    #[tokio::test]
    async fn reload_sends_get() {
        let server = TestServer::spawn(vec![("/api/reload", 200, "{}")]).await;
        assert!(client(server.port).reload().await.expect("reload"));
        let (method, path, _) = server.snapshot();
        assert_eq!("GET", method);
        assert_eq!("/api/reload", path);
    }

    #[tokio::test]
    async fn stop_sends_post() {
        let server = TestServer::spawn(vec![("/api/stop", 200, "{}")]).await;
        assert!(client(server.port).stop().await.expect("stop"));
        let (method, path, _) = server.snapshot();
        assert_eq!("POST", method);
        assert_eq!("/api/stop", path);
    }

    #[tokio::test]
    async fn basic_auth_header_sent() {
        let server = TestServer::spawn(vec![("/api/reload", 200, "{}")]).await;
        let c = FrpcAdminClient::new("127.0.0.1", server.port as i32, Some("admin"), Some("pass"));
        c.reload().await.expect("reload");
        let (_, _, auth) = server.snapshot();
        assert_eq!(Some("Basic YWRtaW46cGFzcw==".to_string()), auth);
    }

    #[tokio::test]
    async fn unexpected_status_reports_http_code() {
        let server = TestServer::spawn(vec![]).await; // 全部 404
        let err = client(server.port).reload().await.expect_err("should fail");
        assert_eq!(404, err.http_code);
    }

    #[tokio::test]
    async fn config_toml_returns_raw_toml() {
        let server = TestServer::spawn(vec![("/api/config", 200, "serverAddr = \"x\"\n")]).await;
        let toml = client(server.port).config_toml().await.expect("config");
        assert!(toml.contains("serverAddr"));
    }
}
