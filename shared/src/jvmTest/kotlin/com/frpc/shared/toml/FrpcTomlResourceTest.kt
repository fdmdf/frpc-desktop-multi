package com.frpc.shared.toml

import com.frpc.shared.model.FrpcConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrpcTomlResourceTest {

    @Test
    fun decode_realFrpcFullExample() {
        val text = TestFile.read("frpc_full_example.toml")
        val config: FrpcConfig = FrpcToml.decode(text)

        assertEquals("0.0.0.0", config.serverAddr)
        assertEquals(7000, config.serverPort)
        assertEquals("12345678", config.auth?.token)
        assertEquals("your_name", config.user)
        assertEquals(7400, config.webServer?.port)
        assertEquals("admin", config.webServer?.user)
        assertEquals(1500, config.udpPacketSize)
        assertEquals("1MB", config.proxies.first { it.name == "ssh" }.transport?.bandwidthLimit)
        assertEquals(listOf("web01.yourdomain.com"), config.proxies.first { it.name == "web01" }.customDomains)
        assertEquals("abcdefg", config.proxies.first { it.name == "secret_tcp" }.secretKey)
        assertTrue(config.proxies.size >= 18, "expected >=18 proxies, got ${config.proxies.size}")
        assertTrue(config.visitors.isNotEmpty(), "visitors should be parsed")
        assertEquals("secret_tcp", config.visitors.first { it.name == "secret_tcp_visitor" }.serverName)
        assertEquals(9000, config.visitors.first { it.name == "secret_tcp_visitor" }.bindPort)
    }

    @Test
    fun decode_reEncodesWithoutLoss() {
        val config = FrpcToml.decode(TestFile.read("frpc_full_example.toml"))
        val again = FrpcToml.decode(FrpcToml.encode(config))
        assertEquals(config, again)
    }
}
