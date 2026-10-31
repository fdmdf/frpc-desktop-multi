//! 对拍 Kotlin `FrpcTomlResourceTest`：frp 官方全量示例（真实世界 TOML）。

use frpc_core::toml::FrpcToml;

fn example() -> String {
    std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/frpc_full_example.toml"
    ))
    .expect("fixture 存在")
}

#[test]
fn decode_real_frpc_full_example() {
    let config = FrpcToml::decode(&example()).expect("decode");

    assert_eq!("0.0.0.0", config.server_addr);
    assert_eq!(7000, config.server_port);
    assert_eq!(Some("12345678"), config.auth.as_ref().map(|a| a.token.as_str()));
    assert_eq!(Some("your_name"), config.user.as_deref());
    assert_eq!(Some(7400), config.web_server.as_ref().map(|w| w.port));
    assert_eq!(Some("admin"), config.web_server.as_ref().map(|w| w.user.as_str()));
    assert_eq!(Some(1500), config.udp_packet_size);

    let ssh = config.proxies.iter().find(|p| p.name == "ssh").expect("ssh proxy");
    assert_eq!(
        Some("1MB"),
        ssh.transport.as_ref().and_then(|t| t.bandwidth_limit.as_deref())
    );
    let web01 = config.proxies.iter().find(|p| p.name == "web01").expect("web01 proxy");
    assert_eq!(
        Some(&vec!["web01.yourdomain.com".to_string()]),
        web01.custom_domains.as_ref()
    );
    let secret_tcp = config
        .proxies
        .iter()
        .find(|p| p.name == "secret_tcp")
        .expect("secret_tcp proxy");
    assert_eq!(Some("abcdefg"), secret_tcp.secret_key.as_deref());

    assert!(config.proxies.len() >= 18, "expected >=18 proxies, got {}", config.proxies.len());
    assert!(!config.visitors.is_empty(), "visitors should be parsed");
    let visitor = config
        .visitors
        .iter()
        .find(|v| v.name == "secret_tcp_visitor")
        .expect("secret_tcp_visitor");
    assert_eq!("secret_tcp", visitor.server_name);
    assert_eq!(9000, visitor.bind_port);
}

#[test]
fn decode_re_encodes_without_loss() {
    let config = FrpcToml::decode(&example()).expect("decode");
    let again = FrpcToml::decode(&FrpcToml::encode(&config)).expect("re-decode");
    assert_eq!(config, again, "官方全量示例经 编码→解码 必须无损");
}
