package com.frpc.shared.model

import kotlinx.serialization.Serializable

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

    val needsRemotePort: Boolean
        get() = this == TCP || this == UDP

    val needsLocalAddr: Boolean
        get() = true

    val needsSecretKey: Boolean
        get() = this == STCP || this == SUDP || this == XTCP

    val domainBased: Boolean
        get() = this == HTTP || this == HTTPS || this == TCPMUX

    val supportsHttpAdvanced: Boolean
        get() = this == HTTP

    val supportsHttpAuth: Boolean
        get() = this == HTTP || this == TCPMUX

    val supportsMultiplexer: Boolean
        get() = this == TCPMUX

    val supportsHttpsPlugin: Boolean
        get() = this == HTTPS

    companion object {
        fun fromId(id: String): ProxyType? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class ProxyConfig(
    val name: String,
    val type: String,
    val enabled: Boolean? = null,
    val localIP: String? = null,
    val localPort: Int? = null,
    val remotePort: Int? = null,
    val subdomain: String? = null,
    val customDomains: List<String>? = null,
    val locations: List<String>? = null,
    val httpUser: String? = null,
    val httpPassword: String? = null,
    val hostHeaderRewrite: String? = null,
    val routeByHTTPUser: String? = null,
    val multiplexer: String? = null,
    val secretKey: String? = null,
    val allowUsers: List<String>? = null,
    val transport: ProxyTransportConfig? = null,

    val plugin: ProxyPluginConfig? = null,
) {
    val proxyType: ProxyType? get() = ProxyType.fromId(type)

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
            plugin = plugin?.sanitized(),
        )
    }
}

@Serializable
data class ProxyTransportConfig(
    val bandwidthLimit: String? = null,
    val bandwidthLimitMode: String? = null,
    val useEncryption: Boolean? = null,
    val useCompression: Boolean? = null,
    val proxyProtocolVersion: String? = null,
)

@Serializable
data class ProxyPluginConfig(
    val type: String,
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

    val isHttpsPlugin: Boolean get() = type == TYPE_HTTPS2HTTP || type == TYPE_TLS2RAW

    companion object {
        const val TYPE_HTTPS2HTTP = "https2http"
        const val TYPE_TLS2RAW = "tls2raw"

        fun none(): ProxyPluginConfig? = null
    }
}

@Serializable
data class VisitorConfig(
    val name: String,
    val type: String,
    val serverName: String,
    val secretKey: String? = null,
    val bindAddr: String = "127.0.0.1",

    val bindPort: Int = 6000,
    val serverUser: String? = null,

    val enabled: Boolean? = null,

    val fallbackTo: String? = null,
    val fallbackTimeoutMs: Int? = null,
    val keepTunnelOpen: Boolean? = null,
) {
    val isXtcp: Boolean get() = type == "xtcp"

    fun sanitizedForType(): VisitorConfig = if (isXtcp) {
        this
    } else {
        copy(keepTunnelOpen = null, fallbackTo = null, fallbackTimeoutMs = null)
    }
}
