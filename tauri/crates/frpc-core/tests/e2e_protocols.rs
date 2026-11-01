//! 八协议端到端验证（对拍 Kotlin `scripts/e2e/e2e-protocols.sh` 的核心语义）。
//!
//! 需要 `FRPC_E2E=1` 才会运行（普通 `cargo test` 自动跳过）：
//! - `frpc verify`：8 种代理类型的 Rust 编码器输出必须被真实 frpc 接受；
//! - 真实隧道：本地起 frps + Rust 编码配置的 frpc + 回声服务，
//!   经远程端口回环验证 TCP 连通（admin API 确认 running 后收发数据）。
//!
//! 环境变量：
//! - `FRPC_BIN`：frpc 可执行文件（默认 `desktopApp/engine/frpc[.exe]`）
//! - `FRPS_BIN`：frps 可执行文件（默认 `.build/e2e/frps[.exe]`）

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use frpc_core::admin::FrpcAdminClient;
use frpc_core::model::*;
use frpc_core::toml::FrpcToml;

fn e2e_enabled() -> bool {
    std::env::var("FRPC_E2E").map(|v| v == "1").unwrap_or(false)
}

fn frpc_bin() -> Option<std::path::PathBuf> {
    if let Ok(p) = std::env::var("FRPC_BIN") {
        return Some(std::path::PathBuf::from(p));
    }
    let exe = if cfg!(windows) { "frpc.exe" } else { "frpc" };
    let p = std::path::PathBuf::from(format!("../../desktopApp/engine/{exe}"));
    p.is_file().then_some(p)
}

fn frps_bin() -> Option<std::path::PathBuf> {
    if let Ok(p) = std::env::var("FRPS_BIN") {
        return Some(std::path::PathBuf::from(p));
    }
    let exe = if cfg!(windows) { "frps.exe" } else { "frps" };
    let p = std::path::PathBuf::from(format!("../../.build/e2e/{exe}"));
    p.is_file().then_some(p)
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

/// 在 frps allowPorts 范围内找一个空闲端口（临时端口池在 49152+，不可复用）。
fn free_port_in_range(start: u16, end: u16) -> u16 {
    for p in start..end {
        if TcpListener::bind(("127.0.0.1", p)).is_ok() {
            return p;
        }
    }
    panic!("no free port in [{start},{end})");
}

fn write_temp_toml(name: &str, config: &FrpcConfig) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join("frpc-core-e2e");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(name);
    std::fs::write(&path, FrpcToml::encode(config)).unwrap();
    path
}

fn base_config(admin_port: u16) -> FrpcConfig {
    FrpcConfig {
        server_addr: "127.0.0.1".into(),
        server_port: 17000,
        login_fail_exit: true,
        auth: Some(AuthConfig {
            method: "token".into(),
            token: "e2etoken".into(),
        }),
        web_server: Some(WebServerConfig {
            addr: "127.0.0.1".into(),
            port: admin_port as i32,
            user: "admin".into(),
            password: "admin".into(),
            pprof_enable: false,
        }),
        ..FrpcConfig::default()
    }
}

/// 8 种代理类型的最小合法配置（与 Kotlin e2e 的用例集一致）。
fn all_protocol_configs(admin_port: u16) -> BTreeMap<&'static str, FrpcConfig> {
    let mut out = BTreeMap::new();
    let mut mk = |name: &'static str, proxy: ProxyConfig| {
        let mut c = base_config(admin_port);
        c.proxies = vec![proxy];
        out.insert(name, c);
    };
    mk(
        "tcp",
        ProxyConfig {
            name: "e2e-tcp".into(),
            type_: "tcp".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18001),
            remote_port: Some(17101),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "udp",
        ProxyConfig {
            name: "e2e-udp".into(),
            type_: "udp".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18002),
            remote_port: Some(17102),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "http",
        ProxyConfig {
            name: "e2e-http".into(),
            type_: "http".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18003),
            subdomain: Some("e2e".into()),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "https",
        ProxyConfig {
            name: "e2e-https".into(),
            type_: "https".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18004),
            subdomain: Some("e2es".into()),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "tcpmux",
        ProxyConfig {
            name: "e2e-tcpmux".into(),
            type_: "tcpmux".into(),
            multiplexer: Some("httpconnect".into()),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18005),
            custom_domains: Some(vec!["e2e-mux.example.com".into()]),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "stcp",
        ProxyConfig {
            name: "e2e-stcp".into(),
            type_: "stcp".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18006),
            secret_key: Some("e2esecret".into()),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "sudp",
        ProxyConfig {
            name: "e2e-sudp".into(),
            type_: "sudp".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18007),
            secret_key: Some("e2esecret".into()),
            ..ProxyConfig::bare()
        },
    );
    mk(
        "xtcp",
        ProxyConfig {
            name: "e2e-xtcp".into(),
            type_: "xtcp".into(),
            local_ip: Some("127.0.0.1".into()),
            local_port: Some(18008),
            secret_key: Some("e2esecret".into()),
            ..ProxyConfig::bare()
        },
    );
    out
}

#[test]
fn all_8_protocols_pass_frpc_verify() {
    if !e2e_enabled() {
        eprintln!("skipped: set FRPC_E2E=1 to run");
        return;
    }
    let Some(frpc) = frpc_bin() else {
        eprintln!("skipped: FRPC_BIN 未找到");
        return;
    };
    let admin_port = free_port();
    for (name, config) in all_protocol_configs(admin_port) {
        let path = write_temp_toml(&format!("verify-{name}.toml"), &config);
        let out = Command::new(&frpc)
            .args(["verify", "-c"])
            .arg(&path)
            .output()
            .expect("frpc verify 可执行");
        assert!(
            out.status.success(),
            "{name} 未通过 frpc verify：\n{}",
            String::from_utf8_lossy(&out.stderr)
        );
    }
}

/// 真实隧道：frps + Rust 编码配置的 frpc + 回声后端 → 远程端口回环收发。
#[test]
fn live_tcp_tunnel_end_to_end() {
    if !e2e_enabled() {
        eprintln!("skipped: set FRPC_E2E=1 to run");
        return;
    }
    let Some(frpc) = frpc_bin() else {
        eprintln!("skipped: FRPC_BIN 未找到");
        return;
    };
    let Some(frps) = frps_bin() else {
        eprintln!("skipped: FRPS_BIN 未找到");
        return;
    };

    let frps_port = free_port();
    let remote_port: u16 = free_port_in_range(20000, 21000);
    let admin_port = free_port();

    // 回声后端
    let backend = TcpListener::bind("127.0.0.1:0").unwrap();
    let backend_port = backend.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for stream in backend.incoming() {
            let Ok(mut stream) = stream else { continue };
            std::thread::spawn(move || {
                let mut buf = [0u8; 4096];
                while let Ok(n) = stream.read(&mut buf) {
                    if n == 0 || stream.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
            });
        }
    });

    // frps
    let frps_toml = format!(
        "bindPort = {frps_port}\nauth.method = \"token\"\nauth.token = \"e2etoken\"\nallowPorts = [{{ start = 20000, end = 21000 }}]\n"
    );
    let frps_cfg = std::env::temp_dir().join("frpc-core-e2e-frps.toml");
    std::fs::write(&frps_cfg, frps_toml).unwrap();
    let mut frps_proc: Child = Command::new(&frps)
        .args(["-c"])
        .arg(&frps_cfg)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("frps 可执行");

    // frpc（Rust 编码器输出）
    let mut config = base_config(admin_port);
    config.server_port = frps_port as i32;
    config.proxies = vec![ProxyConfig {
        name: "e2e-live-tcp".into(),
        type_: "tcp".into(),
        local_ip: Some("127.0.0.1".into()),
        local_port: Some(backend_port as i32),
        remote_port: Some(remote_port as i32),
        ..ProxyConfig::bare()
    }];
    let frpc_cfg = write_temp_toml("live-tcp.toml", &config);
    let mut frpc_proc: Child = Command::new(&frpc)
        .args(["-c"])
        .arg(&frpc_cfg)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("frpc 可执行");

    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let result = rt.block_on(async {
        let client =
            FrpcAdminClient::new("127.0.0.1", admin_port as i32, Some("admin"), Some("admin"));
        // 等 admin API 起来 + 代理 running
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            if Instant::now() > deadline {
                return Err("等待代理 running 超时".to_string());
            }
            if let Ok(snap) = client.status().await {
                if snap
                    .item("e2e-live-tcp")
                    .map(|i| i.status == "running")
                    .unwrap_or(false)
                {
                    break;
                }
            }
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        // 经远程端口回环收发
        tokio::task::spawn_blocking(move || -> Result<(), String> {
            let mut stream = loop {
                match TcpStream::connect(("127.0.0.1", remote_port)) {
                    Ok(s) => break s,
                    Err(_) => std::thread::sleep(Duration::from_millis(300)),
                }
            };
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            stream
                .write_all(b"ping-e2e-12345")
                .map_err(|e| e.to_string())?;
            let mut buf = [0u8; 14];
            stream.read_exact(&mut buf).map_err(|e| e.to_string())?;
            assert_eq!(b"ping-e2e-12345", &buf, "回环数据必须一致");
            Ok(())
        })
        .await
        .unwrap()
    });

    let _ = frpc_proc.kill();
    let _ = frpc_proc.wait();
    let _ = frps_proc.kill();
    let _ = frps_proc.wait();
    if let Err(e) = result {
        panic!("live_tcp_tunnel_end_to_end 失败：{e}");
    }
}
