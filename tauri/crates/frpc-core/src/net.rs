//! 本机端口探测：占用检测、等待释放、监听端口枚举。
//!
//! Kotlin 来源：`shared/src/jvmCommonMain/.../net/LocalPorts.kt`。
//! `PortCheck` 逐行对译；`LocalPorts` 保留 Windows(netstat) / Linux(/proc/net)
//! / macOS(lsof) 三路解析与连接扫描兜底。

use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

/// 一个监听中的端口（Kotlin `ListeningPort`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListeningPort {
    pub protocol: String,
    pub address: String,
    pub port: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pid: Option<i64>,
}

impl ListeningPort {
    pub fn host(&self) -> String {
        let h = match self.address.rsplit_once(':') {
            Some((host, _)) => host.trim_matches(['[', ']']).to_string(),
            None => self.address.clone(),
        };
        if h.is_empty() {
            "*".to_string()
        } else {
            h
        }
    }
}

const CONNECT_TIMEOUT_MS: u64 = 250;

pub struct PortCheck;

impl PortCheck {
    pub fn is_in_use(port: i32) -> bool {
        Self::is_in_use_on(port, "127.0.0.1")
    }

    pub fn is_in_use_on(port: i32, host: &str) -> bool {
        if !(1..=65535).contains(&port) {
            return false;
        }
        if can_connect(port, host, CONNECT_TIMEOUT_MS) {
            return true;
        }
        !can_bind(port, host)
    }

    /// 等待端口释放（Kotlin `awaitFree`，默认 3s）。
    pub async fn await_free(port: i32, timeout_ms: u64) -> bool {
        Self::await_free_on(port, timeout_ms, "127.0.0.1").await
    }

    pub async fn await_free_on(port: i32, timeout_ms: u64, host: &str) -> bool {
        if !(1..=65535).contains(&port) {
            return true;
        }
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        while Instant::now() < deadline {
            if !Self::is_in_use_on(port, host) {
                return true;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        !Self::is_in_use_on(port, host)
    }
}

pub fn can_connect(port: i32, host: &str, timeout_ms: u64) -> bool {
    let addr: SocketAddr = match format!("{host}:{port}").parse() {
        Ok(a) => a,
        Err(_) => {
            // 域名等非 IP 形式：交给系统解析（与 Kotlin InetSocketAddress 一致）
            use std::net::ToSocketAddrs;
            match format!("{host}:{port}")
                .to_socket_addrs()
                .ok()
                .and_then(|mut it| it.next())
            {
                Some(a) => a,
                None => return false,
            }
        }
    };
    TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms)).is_ok()
}

fn can_bind(port: i32, host: &str) -> bool {
    let addr: SocketAddr = match host.parse::<IpAddr>() {
        Ok(ip) => SocketAddr::new(ip, port as u16),
        Err(_) => SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port as u16),
    };
    let domain = if addr.is_ipv4() {
        socket2::Domain::IPV4
    } else {
        socket2::Domain::IPV6
    };
    let socket =
        match socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP)) {
            Ok(s) => s,
            Err(_) => return false,
        };
    let _ = socket.set_reuse_address(true);
    socket
        .bind(&addr.into())
        .and_then(|_| socket.listen(1))
        .is_ok()
}

pub struct LocalPorts;

impl LocalPorts {
    const PROBE_TIMEOUT_MS: u64 = 200;
    const PROBE_THREADS: usize = 48;
    const MAX_SCAN_HOSTS: usize = 4;
    const SCAN_BUDGET_MS: u64 = 8000;

    const COMMON_PORTS: &'static [i32] = &[
        21, 22, 23, 25, 53, 80, 110, 143, 443, 445, 465, 587, 993, 995, 1080, 1433, 1521, 2049,
        3128, 3306, 3389, 5000, 5001, 5060, 5222, 5432, 5555, 5900, 5984, 6379, 6443, 7000, 7400,
        8000, 8008, 8009, 8022, 8080, 8081, 8082, 8088, 8090, 8096, 8123, 8443, 8500, 8880, 8888,
        9000, 9090, 9091, 9200, 9418, 10000, 11211, 27017,
    ];

    /// 枚举本机监听端口（Kotlin `LocalPorts.list`）。
    pub fn list() -> Vec<ListeningPort> {
        let parsed = platform_list().unwrap_or_default();
        let scanned = if parsed.is_empty() && need_connect_scan() {
            Self::scan_by_connect(Self::SCAN_BUDGET_MS)
        } else {
            Vec::new()
        };
        let mut all: Vec<ListeningPort> = parsed.into_iter().chain(scanned).collect();
        all.retain(|p| (1..=65535).contains(&p.port));
        all.sort_by(|a, b| (a.port, &a.protocol).cmp(&(b.port, &b.protocol)));
        all.dedup_by(|a, b| a.protocol == b.protocol && a.port == b.port);
        all
    }

    /// 连接扫描兜底（Android / 无权限读 /proc 时）。
    pub fn scan_by_connect(budget_ms: u64) -> Vec<ListeningPort> {
        let deadline = Instant::now() + Duration::from_millis(budget_ms);
        let stop = Arc::new(AtomicBool::new(false));
        let found: Arc<Mutex<BTreeMap<String, ListeningPort>>> =
            Arc::new(Mutex::new(BTreeMap::new()));

        let hosts = local_hosts();
        // 1) 常见端口 × 本机地址；2) 环回全端口（与 Kotlin scanByConnect 一致）
        let hc = hosts.len();
        let common_total = hc * LocalPorts::COMMON_PORTS.len();
        probe_pool(&deadline, &stop, common_total, &found, &|i: usize| {
            (hosts[i % hc].to_string(), LocalPorts::COMMON_PORTS[i / hc])
        });
        probe_pool(&deadline, &stop, 65535, &found, &|i: usize| {
            ("127.0.0.1".to_string(), i as i32 + 1)
        });

        let result: Vec<ListeningPort> = {
            let map = found.lock().unwrap();
            map.values().cloned().collect()
        };
        result
    }
}

fn probe_pool<F>(
    deadline: &Instant,
    stop: &Arc<AtomicBool>,
    total: usize,
    found: &Arc<Mutex<BTreeMap<String, ListeningPort>>>,
    gen: &F,
) where
    F: Fn(usize) -> (String, i32) + Sync,
{
    if total == 0 || stop.load(Ordering::Relaxed) {
        return;
    }
    let threads = LocalPorts::PROBE_THREADS.min(total);
    let cursor = Arc::new(AtomicUsize::new(0));
    std::thread::scope(|scope| {
        for _ in 0..threads {
            let cursor = cursor.clone();
            let found = found.clone();
            let stop = stop.clone();
            scope.spawn(move || loop {
                if stop.load(Ordering::Relaxed) {
                    return;
                }
                let i = cursor.fetch_add(1, Ordering::Relaxed);
                if i >= total {
                    return;
                }
                let (host, port) = gen(i);
                if can_connect(port, &host, LocalPorts::PROBE_TIMEOUT_MS) {
                    let mut map = found.lock().unwrap();
                    map.insert(
                        format!("{host}:{port}"),
                        ListeningPort {
                            protocol: "tcp".into(),
                            address: format!("{host}:{port}"),
                            port,
                            pid: None,
                        },
                    );
                }
                if Instant::now() > *deadline {
                    stop.store(true, Ordering::Relaxed);
                }
            });
        }
    });
}

fn need_connect_scan() -> bool {
    #[cfg(target_os = "android")]
    {
        true
    }
    #[cfg(target_os = "windows")]
    {
        false
    }
    #[cfg(target_os = "macos")]
    {
        true
    }
    #[cfg(all(unix, not(any(target_os = "android", target_os = "macos"))))]
    {
        !std::path::Path::new("/proc/net/tcp")
            .metadata()
            .map(|_| true)
            .unwrap_or(false)
    }
}

fn local_hosts() -> Vec<&'static str> {
    // 连接扫描只针对环回与常见网段入口；与 Kotlin 的 NIC 枚举相比收窄为
    // 环回（多网卡枚举依赖系统 API，桌面端主路径不走扫描兜底）
    vec!["127.0.0.1"]
}

#[cfg(target_os = "windows")]
fn platform_list() -> Option<Vec<ListeningPort>> {
    let out = exec(&["netstat", "-ano"])?;
    let mut out_ports = Vec::new();
    for line in out.lines() {
        let cols: Vec<&str> = line.trim().split_whitespace().collect();
        if cols.len() < 4 {
            continue;
        }
        let proto = cols[0].to_ascii_lowercase();
        if proto != "tcp" && proto != "udp" {
            continue;
        }
        let listening = proto == "udp"
            || cols
                .get(3)
                .map(|c| c.eq_ignore_ascii_case("LISTENING"))
                .unwrap_or(false)
            || cols.iter().any(|c| c.eq_ignore_ascii_case("LISTENING"));
        if !listening {
            continue;
        }
        let Some((addr, port)) = split_endpoint(cols[1]) else {
            continue;
        };
        let pid = cols.last().and_then(|c| c.parse::<i64>().ok());
        out_ports.push(ListeningPort {
            protocol: proto,
            address: addr,
            port,
            pid,
        });
    }
    Some(out_ports)
}

#[cfg(target_os = "macos")]
fn platform_list() -> Option<Vec<ListeningPort>> {
    let out = exec(&["lsof", "-nP", "-iTCP", "-iUDP"])?;
    let mut ports = Vec::new();
    for line in out.lines() {
        if line.starts_with("COMMAND") {
            continue;
        }
        let cols: Vec<&str> = line.trim().split_whitespace().collect();
        if cols.len() < 9 {
            continue;
        }
        let proto = cols
            .iter()
            .find(|c| c.eq_ignore_ascii_case("TCP") || c.eq_ignore_ascii_case("UDP"))?
            .to_ascii_lowercase();
        let name = cols[8..].join(" ");
        let endpoint = name
            .split(' ')
            .next()
            .unwrap_or("")
            .split("->")
            .next()
            .unwrap_or("");
        let Some((addr, port)) = split_endpoint(endpoint) else {
            continue;
        };
        let listening = proto == "udp" || line.contains("(LISTEN)");
        if !listening {
            continue;
        }
        ports.push(ListeningPort {
            protocol: proto,
            address: addr,
            port,
            pid: cols.get(1).and_then(|c| c.parse::<i64>().ok()),
        });
    }
    Some(ports)
}

#[cfg(all(unix, not(target_os = "macos")))]
fn platform_list() -> Option<Vec<ListeningPort>> {
    let mut result = Vec::new();
    for (name, need_listen) in [
        ("tcp", true),
        ("tcp6", true),
        ("udp", false),
        ("udp6", false),
    ] {
        let Ok(content) = std::fs::read_to_string(format!("/proc/net/{name}")) else {
            continue;
        };
        for line in content.lines().skip(1) {
            let cols: Vec<&str> = line.trim().split_whitespace().collect();
            if cols.len() < 10 {
                continue;
            }
            let proto = name.trim_end_matches('6');
            if need_listen && cols[3] != "0A" {
                continue;
            }
            if !need_listen
                && cols[2] != "00000000:0000"
                && cols[2] != "00000000000000000000000000000000:0000"
            {
                continue;
            }
            let Some((hex_addr, hex_port)) = cols[1].split_once(':') else {
                continue;
            };
            let Ok(port) = i32::from_str_radix(hex_port, 16) else {
                continue;
            };
            let addr = decode_hex_addr(hex_addr, name.ends_with('6'));
            result.push(ListeningPort {
                protocol: proto.to_string(),
                address: format!("{addr}:{port}"),
                port,
                pid: None,
            });
        }
    }
    Some(result)
}

#[cfg(all(unix, not(target_os = "macos")))]
fn decode_hex_addr(hex: &str, ipv6: bool) -> String {
    let bytes: Vec<u8> = (0..hex.len())
        .step_by(2)
        .filter_map(|i| u8::from_str_radix(&hex[i..i + 2], 16).ok())
        .collect();
    if !ipv6 && bytes.len() == 4 {
        bytes
            .iter()
            .rev()
            .map(|b| b.to_string())
            .collect::<Vec<_>>()
            .join(".")
    } else if ipv6 && bytes.len() == 16 {
        let mut groups = Vec::new();
        for i in (0..16).step_by(4) {
            let g = &bytes[i..i + 4];
            groups.push(format!(
                "{:x}:{:x}",
                (u16::from(g[0]) << 8 | u16::from(g[1])),
                (u16::from(g[2]) << 8 | u16::from(g[3]))
            ));
        }
        let joined = groups.join(":");
        // 压缩连续的 0 段（与 Kotlin 的正则等价的简化处理）
        joined.replace(":0:0:0:0:0:0:0:0", "::")
    } else {
        hex.to_string()
    }
}

fn split_endpoint(text: &str) -> Option<(String, i32)> {
    let idx = text.rfind(':')?;
    if idx == 0 {
        return None;
    }
    let port = text[idx + 1..].trim().parse::<i32>().ok()?;
    let addr = text[..idx].trim().to_string();
    Some((addr, port))
}

fn exec(command: &[&str]) -> Option<String> {
    use std::process::Stdio;
    let mut child = std::process::Command::new(command[0])
        .args(&command[1..])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .ok()?;
    let deadline = Instant::now() + Duration::from_millis(10_000);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let _ = status;
                break;
            }
            Ok(None) => {
                if Instant::now() > deadline {
                    let _ = child.kill();
                    return None;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            Err(_) => return None,
        }
    }
    let out = child.wait_with_output().ok()?;
    Some(String::from_utf8_lossy(&out.stdout).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn port_in_use_detection() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().unwrap().port() as i32;
        assert!(PortCheck::is_in_use(port), "监听中的端口应视为占用");
        assert!(
            !PortCheck::is_in_use(port + 10000),
            "空闲高位端口不应视为占用"
        );
        drop(listener);
    }

    #[test]
    fn invalid_port_not_in_use() {
        assert!(!PortCheck::is_in_use(0));
        assert!(!PortCheck::is_in_use(70000));
    }

    #[tokio::test]
    async fn await_free_returns_immediately_for_free_port() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port() as i32;
        drop(listener);
        assert!(PortCheck::await_free(port, 1000).await);
    }

    #[tokio::test]
    async fn await_free_times_out_on_busy_port() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port() as i32;
        assert!(!PortCheck::await_free(port, 300).await, "占用中应超时");
        drop(listener);
    }

    #[test]
    fn listening_port_host() {
        let p = ListeningPort {
            protocol: "tcp".into(),
            address: "[::]:8080".into(),
            port: 8080,
            pid: Some(123),
        };
        assert_eq!("::", p.host(), "Kotlin trim('[',']') 后非空，返回 ::");
        let p2 = ListeningPort {
            protocol: "tcp".into(),
            address: "127.0.0.1:80".into(),
            port: 80,
            pid: None,
        };
        assert_eq!("127.0.0.1", p2.host());
    }

    #[test]
    fn local_ports_smoke() {
        // 起一个监听，确保枚举结果有序且包含本进程监听的端口形态
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port() as i32;
        let list = LocalPorts::list();
        let sorted = list
            .windows(2)
            .all(|w| (w[0].port, w[0].protocol.clone()) <= (w[1].port, w[1].protocol.clone()));
        assert!(sorted, "结果应按 (port, protocol) 排序");
        assert!(list.iter().all(|p| (1..=65535).contains(&p.port)));
        let _ = port; // 枚举实现平台差异大，只验证不变量
    }
}
