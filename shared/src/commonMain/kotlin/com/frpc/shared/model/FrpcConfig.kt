package com.frpc.shared.model

import kotlinx.serialization.Serializable

/**
 * frpc 全局配置（对应 frpc.toml 顶层字段与各 section）。
 * 可空 section 为 null 时不写入 frpc.toml，由 frpc 使用自身默认值：
 * - webServer = null  → 不启动 admin API（端口 0 不监听，见 frp client/service.go）
 * - log/transport = null → 使用 frpc 默认值（console/info、tcp+tls）
 */
@Serializable
data class FrpcConfig(
    val serverAddr: String = "",
    val serverPort: Int = 7000,
    /**
     * 代理名前缀，最终名为 {user}.{proxy}。
     * 可空：null 时不写入 frpc.toml（frp 默认无前缀），避免导出 user = "" 这类无意义空串。
     */
    val user: String? = null,
    val clientID: String? = null,
    /**
     * 首次登录失败是否退出。
     * 默认 false：手机网络频繁切换，应让 frpc 持续重连而非退出（桌面端 frpc 默认为 true）。
     */
    val loginFailExit: Boolean = false,
    /**
     * xtcp 打洞用的 STUN 服务器，格式 host:port。
     * 空则不写入配置，frpc 用自带默认值（stun.easyvoip.com:3478）；
     * 默认 STUN 被墙/不可达时，xtcp 会一直报 "wait response from stun server timeout"。
     */
    val natHoleStunServer: String? = null,
    val log: LogConfig? = null,
    /**
     * 认证配置。
     * - null 或 method = "none"：不写 `[auth]` 段（frp 只接受 token / oidc，
     *   把 "none" 写进 TOML 会被 frpc 判为 `invalid auth method` 直接退出）
     * - 编码时 "none" 会被翻译成「整段省略」，解码时缺少该段则还原成 "none"
     * - 默认 null 而非 AuthConfig()：只有这样才能区分「TOML 里没有 [auth]」和「写了 token 段」
     *   （新建档案的入口会显式传 AuthConfig()，让用户看到 Token 输入框）
     */
    val auth: AuthConfig? = null,
    /**
     * Admin API（webServer）默认开启：App 的在线状态/资源监控依赖它。
     * 仅监听 127.0.0.1，不对外暴露；置为 null 可关闭。
     */
    val webServer: WebServerConfig? = WebServerConfig(),
    val transport: TransportConfig? = null,
    val udpPacketSize: Int? = null,
    /**
     * 传给服务端插件的元数据（frps 开 multiuser 插件时放 token）。
     * 为空不写 [metadatas] 段，避免生成空表。
     */
    val metadatas: Map<String, String>? = null,
    val proxies: List<ProxyConfig> = emptyList(),
    val visitors: List<VisitorConfig> = emptyList(),
)

@Serializable
data class LogConfig(
    /** console 或真实日志文件路径；安卓端固定使用 console，由 App 采集 stdout */
    val to: String = "console",
    /** trace/debug/info/warn/error */
    val level: String = "info",
    val maxDays: Int = 3,
    /**
     * 是否关闭控制台颜色。桌面端日志由 App 采集后自己着色，关掉可少一堆 ANSI 转义；
     * 为 null 时不写入，由 frpc 用默认值（false）。
     */
    val disablePrintColor: Boolean? = null,
)

@Serializable
data class AuthConfig(
    /** token 或 oidc */
    val method: String = "token",
    val token: String = "",
)

@Serializable
data class WebServerConfig(
    /** admin API 监听地址，安卓端仅本机访问 */
    val addr: String = "127.0.0.1",
    val port: Int = 7400,
    val user: String = "admin",
    val password: String = "admin",
    val pprofEnable: Boolean = false,
)

@Serializable
data class TransportConfig(
    /** tcp/kcp/quic/websocket/wss */
    val protocol: String = "tcp",
    val tcpMux: Boolean = true,
    val poolCount: Int = 0,
    val tls: TlsConfig? = null,
    val dialServerTimeout: Int = 10,
    val dialServerKeepalive: Int = 7200,
    /** 连接 frps 时使用的本地 IP，为空则不绑定 */
    val connectServerLocalIP: String? = null,
    /** http/https/socks5/socks5h 代理，仅 protocol=tcp 有效 */
    val proxyURL: String? = null,
    /**
     * 心跳间隔 / 超时（秒）。frpc 默认 10 / 90；
     * 为 null 时不写入，保持 frpc 自身默认，避免把用户没改过的值固化进配置。
     */
    val heartbeatInterval: Int? = null,
    val heartbeatTimeout: Int? = null,
    /** 多路复用保活间隔（秒），仅 tcpMux = true 时有效；frpc 默认 30 */
    val tcpMuxKeepaliveInterval: Int? = null,
)

@Serializable
data class TlsConfig(
    /** frp >= v0.50.0 默认开启 TLS */
    val enable: Boolean = true,
    val serverName: String? = null,
    /** 双向 TLS：客户端证书与私钥 */
    val certFile: String? = null,
    val keyFile: String? = null,
    /** 校验 frps 证书用的 CA（自签时填） */
    val trustedCaFile: String? = null,
    /**
     * 关闭自定义 TLS 首字节（frp 默认 true）。
     * 可空：为 null 时不写入——写死 false 会让某些 frps 直接握手失败。
     */
    val disableCustomTLSFirstByte: Boolean? = null,
)
