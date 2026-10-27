package com.frpc.shared.model

import kotlinx.serialization.Serializable

/** frpc 代理类型，与 frp 服务端配置一一对应 */
enum class ProxyType(val id: String) {
    TCP("tcp"),
    UDP("udp"),
    HTTP("http"),
    HTTPS("https"),
    STCP("stcp"),
    SUDP("sudp"),
    XTCP("xtcp"),
    TCPMUX("tcpmux"),
    ;

    /**
     * 是否使用 remote_port（frps 监听端口）。
     * 注意：仅 tcp/udp 支持（见 frp pkg/config/legacy/proxy.go：只有
     * TCPProxyConf/UDPProxyConf 有 RemotePort，tcpmux 走 vhost 域名）。
     */
    val needsRemotePort: Boolean
        get() = this == TCP || this == UDP

    /** 本地服务地址（local_ip/local_port）：所有类型的基础字段 */
    val needsLocalAddr: Boolean
        get() = true

    /** 是否需要 secretKey */
    val needsSecretKey: Boolean
        get() = this == STCP || this == SUDP || this == XTCP

    /** 是否基于域名访问（http/https/tcpmux，DomainConf） */
    val domainBased: Boolean
        get() = this == HTTP || this == HTTPS || this == TCPMUX

    /** 是否支持 locations / host_header_rewrite（仅 http） */
    val supportsHttpAdvanced: Boolean
        get() = this == HTTP

    /** 是否支持 http_user / http_password（http 与 tcpmux） */
    val supportsHttpAuth: Boolean
        get() = this == HTTP || this == TCPMUX

    /** 是否支持 multiplexer（仅 tcpmux） */
    val supportsMultiplexer: Boolean
        get() = this == TCPMUX

    /** 是否支持 HTTPS 插件（https2http / tls2raw，把 TLS 终结在 frpc 侧） */
    val supportsHttpsPlugin: Boolean
        get() = this == HTTPS

    companion object {
        fun fromId(id: String): ProxyType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 单个代理规则（对应 [[proxies]]）。
 * 可选字段为 null 时不写入 frpc.toml，由 frpc 使用自身默认值。
 */
@Serializable
data class ProxyConfig(
    val name: String,
    val type: String,
    /** false 时生成 enabled = false 禁用该代理 */
    val enabled: Boolean? = null,
    val localIP: String? = null,
    val localPort: Int? = null,
    // ---- tcp / udp / tcpmux ----
    val remotePort: Int? = null,
    // ---- http / https / tcpmux ----
    val subdomain: String? = null,
    val customDomains: List<String>? = null,
    val locations: List<String>? = null,
    val httpUser: String? = null,
    val httpPassword: String? = null,
    val hostHeaderRewrite: String? = null,
    val routeByHTTPUser: String? = null,
    // ---- tcpmux ----
    val multiplexer: String? = null,
    // ---- stcp / sudp / xtcp ----
    val secretKey: String? = null,
    /** 允许访问的 user 前缀列表；留空 = 仅同 user 可访问，["*"] = 允许所有 user */
    val allowUsers: List<String>? = null,
    /** 每代理 transport 子表 */
    val transport: ProxyTransportConfig? = null,
    /**
     * 代理插件（[proxies.plugin]）。只建模 UI 用到的四种字段；
     * 其它插件类型从 TOML 导入时原样保留 type，不认识的键会被忽略。
     */
    val plugin: ProxyPluginConfig? = null,
) {
    val proxyType: ProxyType? get() = ProxyType.fromId(type)

    /**
     * 清理当前类型不支持的字段。
     * frp 按代理类型严格解码配置（未知字段直接报错退出），例如 sudp 带
     * remotePort 会让 frpc 启动失败。导入外部 TOML 或切换代理类型时调用，
     * 保证生成的配置一定能被 frpc/frps 接受。
     */
    fun sanitizedForType(): ProxyConfig {
        val type = proxyType ?: return this
        return copy(
            remotePort = if (type.needsRemotePort) remotePort else null,
            subdomain = if (type.domainBased) subdomain else null,
            customDomains = if (type.domainBased) customDomains else null,
            locations = if (type.supportsHttpAdvanced) locations else null,
            httpUser = if (type.supportsHttpAuth) httpUser else null,
            httpPassword = if (type.supportsHttpAuth) httpPassword else null,
            hostHeaderRewrite = if (type.supportsHttpAdvanced) hostHeaderRewrite else null,
            routeByHTTPUser = if (type.supportsHttpAuth) routeByHTTPUser else null,
            multiplexer = if (type.supportsMultiplexer) multiplexer else null,
            secretKey = if (type.needsSecretKey) secretKey else null,
            allowUsers = if (type.needsSecretKey) allowUsers else null,
            // plugin 不按类型裁剪：frp 的 plugin 挂在 ProxyBaseConfig 上，各类型都可能出现，
            // 导入的 tcp/socks5 之类插件必须原样保留，否则用户配置会被悄悄改掉。
            plugin = plugin?.sanitized(),
        )
    }
}

@Serializable
data class ProxyTransportConfig(
    /** 例如 "1MB" / "500KB" */
    val bandwidthLimit: String? = null,
    val bandwidthLimitMode: String? = null,
    val useEncryption: Boolean? = null,
    val useCompression: Boolean? = null,
    /** v1 / v2，https 类型代理转发连接信息给本地服务 */
    val proxyProtocolVersion: String? = null,
)

/**
 * [proxies.plugin] 子表。
 * - https2http：frpc 终结 TLS，再以 HTTP 转发给本地服务（本地服务不用配证书）
 * - tls2raw：frpc 终结 TLS，把解密后的原始字节转给本地服务（WebSocket 等场景）
 * 两者都要求 localAddr + crtPath + keyPath。
 */
@Serializable
data class ProxyPluginConfig(
    /** https2http / tls2raw / socks5 / static_file / http_proxy ... 原样写回 */
    val type: String,
    /** 本地服务地址 host:port；插件模式下 localIP/localPort 不生效 */
    val localAddr: String? = null,
    val crtPath: String? = null,
    val keyPath: String? = null,
    val hostHeaderRewrite: String? = null,
) {
    fun sanitized(): ProxyPluginConfig = copy(
        type = type.trim(),
        localAddr = localAddr?.trim()?.ifBlank { null },
        crtPath = crtPath?.trim()?.ifBlank { null },
        keyPath = keyPath?.trim()?.ifBlank { null },
        hostHeaderRewrite = hostHeaderRewrite?.trim()?.ifBlank { null },
    )

    /** 是否为 UI 可编辑的 HTTPS 插件 */
    val isHttpsPlugin: Boolean get() = type == TYPE_HTTPS2HTTP || type == TYPE_TLS2RAW

    companion object {
        const val TYPE_HTTPS2HTTP = "https2http"
        const val TYPE_TLS2RAW = "tls2raw"

        /** 清空插件：null 表示不写 [proxies.plugin] */
        fun none(): ProxyPluginConfig? = null
    }
}

/**
 * 访问者规则（对应 [[visitors]]），用于 stcp/xtcp 场景。
 * 注意：frp v1 TOML schema 没有 keepTunnelOpen（那是旧版 INI 的 keep_tunnel_open），
 * 多写这个字段 frpc 会直接退出（unknown field），因此模型里不能出现它。
 */
@Serializable
data class VisitorConfig(
    val name: String,
    /** stcp / xtcp */
    val type: String,
    /** 要访问的服务端代理名 */
    val serverName: String,
    val secretKey: String? = null,
    val bindAddr: String = "127.0.0.1",
    /**
     * 访问者在本机监听的端口。
     * frp 的 validateVisitorBaseConfig 把 0 视为「没填」并报错退出（bind port is required），
     * 所以默认给一个可直接使用的端口；要「只接收其他访问者转发、不监听」必须填负数（-1），
     * 且 sudp 不支持负数。
     */
    val bindPort: Int = 6000,
    val serverUser: String? = null,
    /**
     * 打洞失败时转交给哪个访问者（通常是同目标的 stcp 访问者，数据改走 frps 中转）。
     * frp 在 xtcp 建连失败后把连接交给它，是「xtcp 不通也能用」的兜底。
     */
    val fallbackTo: String? = null,
    /** 等多久算打洞失败（毫秒）；留空由 frpc 用默认值 1000 */
    val fallbackTimeoutMs: Int? = null,
    // ---- 仅 xtcp：frp v1 里只有 XTCPVisitorConfig 有这些字段，stcp/sudp visitor 带上会 unknown field ----
    val keepTunnelOpen: Boolean? = null,
) {
    /** 是否为 xtcp 访问者 */
    val isXtcp: Boolean get() = type == "xtcp"

    /** 清理当前类型不支持的字段（frp 对 visitor 同样按类型严格解码） */
    fun sanitizedForType(): VisitorConfig = if (isXtcp) {
        this
    } else {
        copy(keepTunnelOpen = null, fallbackTo = null, fallbackTimeoutMs = null)
    }
}
