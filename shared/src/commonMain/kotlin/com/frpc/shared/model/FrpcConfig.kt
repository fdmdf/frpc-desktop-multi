package com.frpc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class FrpcConfig(
    val serverAddr: String = "",
    val serverPort: Int = 7000,

    val user: String? = null,
    val clientID: String? = null,

    val loginFailExit: Boolean = false,

    val natHoleStunServer: String? = null,
    val log: LogConfig? = null,

    val auth: AuthConfig? = null,

    val webServer: WebServerConfig? = WebServerConfig(),
    val transport: TransportConfig? = null,
    val udpPacketSize: Int? = null,

    val metadatas: Map<String, String>? = null,
    val proxies: List<ProxyConfig> = emptyList(),
    val visitors: List<VisitorConfig> = emptyList(),
)

@Serializable
data class LogConfig(
    val to: String = "console",
    val level: String = "info",
    val maxDays: Int = 3,

    val disablePrintColor: Boolean? = null,
)

@Serializable
data class AuthConfig(
    val method: String = "token",
    val token: String = "",
)

@Serializable
data class WebServerConfig(
    val addr: String = "127.0.0.1",
    val port: Int = 7400,
    val user: String = "admin",
    val password: String = "admin",
    val pprofEnable: Boolean = false,
)

@Serializable
data class TransportConfig(
    val protocol: String = "tcp",
    val tcpMux: Boolean = true,
    val poolCount: Int = 0,
    val tls: TlsConfig? = null,
    val dialServerTimeout: Int = 10,
    val dialServerKeepalive: Int = 7200,
    val connectServerLocalIP: String? = null,
    val proxyURL: String? = null,

    val heartbeatInterval: Int? = null,
    val heartbeatTimeout: Int? = null,
    val tcpMuxKeepaliveInterval: Int? = null,
)

@Serializable
data class TlsConfig(
    val enable: Boolean = true,
    val serverName: String? = null,
    val certFile: String? = null,
    val keyFile: String? = null,
    val trustedCaFile: String? = null,

    val disableCustomTLSFirstByte: Boolean? = null,
)
