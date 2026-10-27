package com.frpc.shared.toml

import com.frpc.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrpcTomlTest {

    @Test
    fun encode_omitsRemotePortWhenEmpty() {
        // 留空 = 交给 frps 随机分配：不能写出 null，也不能写出 0（frp 两种都当随机，但 0 更易误读）
        val config = FrpcConfig(
            serverAddr = "frps.example.com",
            proxies = listOf(
                ProxyConfig(name = "web", type = "tcp", localIP = "127.0.0.1", localPort = 8080),
            ),
        )
        val toml = FrpcToml.encode(config)
        assertFalse(toml.contains("remotePort"), "留空时不应写出 remotePort：\n$toml")
    }

    @Test
    fun roundTrip_fullConfig() {
        val config = FrpcConfig(
            serverAddr = "frps.example.com",
            serverPort = 7000,
            user = "alice",
            clientID = "phone-01",
            loginFailExit = true,
            log = LogConfig(to = "console", level = "debug", maxDays = 3),
            auth = AuthConfig(method = "token", token = "secret-token"),
            webServer = WebServerConfig(
                addr = "127.0.0.1",
                port = 7400,
                user = "admin",
                password = "admin",
                pprofEnable = false,
            ),
            transport = TransportConfig(
                protocol = "tcp",
                tcpMux = true,
                poolCount = 2,
                tls = TlsConfig(enable = true, serverName = "frps.example.com"),
                connectServerLocalIP = null,
                proxyURL = null,
            ),
            udpPacketSize = 1500,
            proxies = listOf(
                ProxyConfig(
                    name = "ssh", type = "tcp",
                    localIP = "127.0.0.1", localPort = 22, remotePort = 6001,
                    transport = ProxyTransportConfig(
                        bandwidthLimit = "1MB",
                        useEncryption = true,
                        useCompression = false,
                    ),
                ),
                ProxyConfig(
                    name = "web", type = "http",
                    localIP = "127.0.0.1", localPort = 80,
                    subdomain = "web01",
                    customDomains = listOf("web01.example.com"),
                    locations = listOf("/", "/pic"),
                    httpUser = "u", httpPassword = "p",
                    hostHeaderRewrite = "example.com",
                ),
                ProxyConfig(
                    name = "dns", type = "udp",
                    localIP = "192.168.1.1", localPort = 53, remotePort = 6002,
                ),
                ProxyConfig(
                    name = "secret_tcp", type = "stcp",
                    localIP = "127.0.0.1", localPort = 22,
                    secretKey = "abcdefg",
                    allowUsers = listOf("*"),
                ),
                ProxyConfig(
                    name = "disabled_ssh", type = "tcp",
                    localIP = "127.0.0.1", localPort = 22, remotePort = 6003,
                    enabled = false,
                ),
            ),
            visitors = listOf(
                VisitorConfig(
                    name = "secret_tcp_visitor", type = "stcp",
                    serverName = "secret_tcp", secretKey = "abcdefg",
                    bindAddr = "127.0.0.1", bindPort = 9000,
                ),
            ),
        )

        val toml = FrpcToml.encode(config)
        println("---- generated frpc.toml ----")
        println(toml)
        println("-----------------------------")

        val decoded = FrpcToml.decode(toml)
        assertEquals(config, decoded)
    }

    @Test
    fun roundTrip_minimalConfig_omitsOptionalSections() {
        val config = FrpcConfig(
            serverAddr = "1.2.3.4",
            serverPort = 7000,
            auth = AuthConfig(method = "token", token = "t"),
            webServer = null,
            transport = null,
            log = null,
        )
        val toml = FrpcToml.encode(config)
        assertTrue("webServer" !in toml, "webServer section should be omitted")
        assertTrue("transport" !in toml, "transport section should be omitted")
        assertTrue("[[proxies]]" !in toml, "no proxies should be emitted")
        val decoded = FrpcToml.decode(toml)
        // 解码时 webServer 取默认值（App 默认开启 Admin API），其余保持
        assertEquals(config.copy(webServer = WebServerConfig()), decoded)
    }

    @Test
    fun encode_containsExpectedKeys() {
        val toml = FrpcToml.encode(
            FrpcConfig(
                serverAddr = "frps.example.com",
                auth = AuthConfig(token = "tok"),
                webServer = WebServerConfig(port = 7400),
                proxies = listOf(
                    ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6000),
                ),
            ),
        )
        assertTrue(toml.contains("serverAddr = \"frps.example.com\""))
        assertTrue(toml.contains("serverPort = 7000"))
        assertTrue(toml.contains("[[proxies]]"))
        assertTrue(toml.contains("name = \"ssh\""))
        assertTrue(toml.contains("type = \"tcp\""))
        assertTrue(toml.contains("[webServer]"))
        assertTrue(toml.contains("port = 7400"))
    }

    /** 回归：keepTunnelOpen 只属于 xtcp visitor；stcp visitor 带上会让 frpc 直接退出 */
    @Test
    fun visitor_xtcpOnlyFields_sanitizedPerType() {
        val toml = """
            serverAddr = "127.0.0.1"
            [[visitors]]
            name = "v-stcp"
            type = "stcp"
            serverName = "p"
            secretKey = "k"
            bindAddr = "127.0.0.1"
            bindPort = 6000
            keepTunnelOpen = true
        """.trimIndent()
        val stcp = FrpcToml.decode(toml)
        assertEquals(null, stcp.visitors.first().keepTunnelOpen)
        val stcpToml = FrpcToml.encode(stcp)
        assertTrue(!stcpToml.contains("keepTunnelOpen"), stcpToml)

        // xtcp 保留该字段
        val xtcp = stcp.copy(
            visitors = stcp.visitors.map { it.copy(type = "xtcp", keepTunnelOpen = true) },
        )
        val xtcpToml = FrpcToml.encode(xtcp)
        assertTrue(xtcpToml.contains("keepTunnelOpen = true"), xtcpToml)
        assertTrue(FrpcToml.decode(xtcpToml).visitors.first().keepTunnelOpen == true)
    }
}
