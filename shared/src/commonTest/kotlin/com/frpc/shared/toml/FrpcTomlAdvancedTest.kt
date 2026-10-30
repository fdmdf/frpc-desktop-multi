package com.frpc.shared.toml

import com.frpc.shared.model.AuthConfig
import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.LogConfig
import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.ProxyPluginConfig
import com.frpc.shared.model.TlsConfig
import com.frpc.shared.model.TransportConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrpcTomlAdvancedTest {

    @Test
    fun roundTrip_transportHeartbeatAndTlsFiles() {
        val config = FrpcConfig(
            serverAddr = "frps.example.com",
            auth = AuthConfig(method = "token", token = "t"),
            transport = TransportConfig(
                protocol = "tcp",
                tcpMux = true,
                poolCount = 2,
                dialServerTimeout = 10,
                dialServerKeepalive = -1,
                connectServerLocalIP = "192.168.1.10",
                proxyURL = "socks5://127.0.0.1:1080",
                heartbeatInterval = 15,
                heartbeatTimeout = 120,
                tcpMuxKeepaliveInterval = 45,
                tls = TlsConfig(
                    enable = true,
                    serverName = "frps.example.com",
                    certFile = "/etc/frp/client.crt",
                    keyFile = "/etc/frp/client.key",
                    trustedCaFile = "/etc/frp/ca.crt",
                    disableCustomTLSFirstByte = false,
                ),
            ),
            log = LogConfig(level = "debug", maxDays = 7, disablePrintColor = true),
            metadatas = mapOf("token" to "meta-token"),
        )

        val toml = FrpcToml.encode(config)
        println("---- advanced toml ----\n$toml\n----------------------")
        assertTrue(toml.contains("heartbeatInterval = 15"), toml)
        assertTrue(toml.contains("tcpMuxKeepaliveInterval = 45"), toml)
        assertTrue(toml.contains("certFile = "), toml)
        assertTrue(toml.contains("disablePrintColor = true"), toml)
        assertTrue(toml.contains("token = \"meta-token\""), toml)

        assertEquals(config, FrpcToml.decode(toml))
    }

    @Test
    fun encode_omitsUnsetOptionalTransportFields() {
        val toml = FrpcToml.encode(
            FrpcConfig(
                serverAddr = "1.2.3.4",
                auth = AuthConfig(token = "t"),
                transport = TransportConfig(tls = TlsConfig()),
                metadatas = null,
            ),
        )
        assertFalse(toml.contains("heartbeatInterval"), toml)
        assertFalse(toml.contains("tcpMuxKeepaliveInterval"), toml)
        assertFalse(toml.contains("disableCustomTLSFirstByte"), toml)
        assertFalse(toml.contains("metadatas"), toml)
        assertFalse(toml.contains("certFile"), toml)
    }

    @Test
    fun roundTrip_https2httpPlugin() {
        val config = FrpcConfig(
            serverAddr = "frps.example.com",
            auth = AuthConfig(token = "t"),
            proxies = listOf(
                ProxyConfig(
                    name = "web-https",
                    type = "https",
                    localIP = "127.0.0.1",
                    localPort = 8080,
                    customDomains = listOf("web.example.com"),
                    plugin = ProxyPluginConfig(
                        type = ProxyPluginConfig.TYPE_HTTPS2HTTP,
                        localAddr = "127.0.0.1:8080",
                        crtPath = "/etc/frp/web.crt",
                        keyPath = "/etc/frp/web.key",
                    ),
                ),
            ),
        )
        val toml = FrpcToml.encode(config)
        println("---- plugin toml ----\n$toml\n---------------------")
        assertTrue(toml.contains("plugin"), toml)
        assertTrue(toml.contains("https2http"), toml)
        assertEquals(config, FrpcToml.decode(toml))
    }

    @Test
    fun import_keepsPluginForNonHttpsType() {
        val toml = """
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
        """.trimIndent()
        val decoded = FrpcToml.decode(toml)
        val plugin = decoded.proxies.first().plugin
        assertEquals("socks5", plugin?.type)
        assertTrue(FrpcToml.encode(decoded).contains("socks5"))
    }

    @Test
    fun authNone_omitsSectionEntirely() {
        val toml = FrpcToml.encode(
            FrpcConfig(serverAddr = "127.0.0.1", auth = AuthConfig(method = "none", token = "")),
        )
        assertFalse(toml.contains("[auth]"), toml)
        assertFalse(toml.contains("method"), toml)
        assertEquals("none", FrpcToml.decode(toml).auth?.method)
    }
}
