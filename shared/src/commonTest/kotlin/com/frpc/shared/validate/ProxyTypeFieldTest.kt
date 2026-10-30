package com.frpc.shared.validate

import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.ProxyType
import com.frpc.shared.model.VisitorConfig
import com.frpc.shared.toml.FrpcToml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代理类型字段兼容性测试。
 * 背景：frp 按代理类型严格解码配置（未知字段直接报错退出），
 * 例如 sudp/tcpmux 带 remotePort 会让 frpc 启动失败（OpenWRT frps 实测）。
 */
class ProxyTypeFieldTest {

    private fun baseConfig(proxy: ProxyConfig) = FrpcConfig(
        serverAddr = "frps.example.com",
        auth = com.frpc.shared.model.AuthConfig(token = "tok"),
        proxies = listOf(proxy),
    )

    @Test
    fun remotePort_onlyAllowedForTcpAndUdp() {
        // tcp/udp 合法
        listOf(ProxyType.TCP, ProxyType.UDP).forEach { type ->
            val issues = ConfigValidator.validate(
                baseConfig(ProxyConfig(name = "p", type = type.id, localIP = "127.0.0.1", localPort = 22, remotePort = 6001)),
            )
            assertTrue(issues.none { it.reason == Reason.FIELD_NOT_SUPPORTED_FOR_TYPE }, "$type 应允许 remotePort")
        }
        // tcpmux/stcp/sudp/xtcp/http/https 带 remotePort 应报错
        listOf(ProxyType.TCPMUX, ProxyType.STCP, ProxyType.SUDP, ProxyType.XTCP, ProxyType.HTTP, ProxyType.HTTPS).forEach { type ->
            val issues = ConfigValidator.validate(
                baseConfig(
                    ProxyConfig(
                        name = "p", type = type.id, localIP = "127.0.0.1", localPort = 80,
                        remotePort = 6001,
                        subdomain = "web",
                        secretKey = "sk",
                        multiplexer = "httpconnect",
                    ),
                ),
            )
            assertTrue(
                issues.any { it.reason == Reason.FIELD_NOT_SUPPORTED_FOR_TYPE },
                "$type 带 remotePort 应报 FIELD_NOT_SUPPORTED_FOR_TYPE",
            )
        }
    }

    @Test
    fun sanitizedForType_stripsUnsupportedFields() {
        val dirty = ProxyConfig(
            name = "p", type = "sudp", localIP = "127.0.0.1", localPort = 53,
            remotePort = 6001,
            subdomain = "web",
            customDomains = listOf("a.com"),
            locations = listOf("/"),
            httpUser = "u",
            httpPassword = "p",
            hostHeaderRewrite = "h",
            multiplexer = "httpconnect",
        )
        val clean = dirty.sanitizedForType()
        assertEquals(null, clean.remotePort)
        assertEquals(null, clean.subdomain)
        assertEquals(null, clean.customDomains)
        assertEquals(null, clean.locations)
        assertEquals(null, clean.httpUser)
        assertEquals(null, clean.multiplexer)
        // sudp 基础字段保留
        assertEquals("sudp", clean.type)
        assertEquals(53, clean.localPort)
    }

    @Test
    fun sanitizedForType_stripsFallbackFromNonXtcpVisitor() {
        val xtcp = VisitorConfig(
            name = "x", type = "xtcp", serverName = "p", secretKey = "k",
            fallbackTo = "x-stcp", fallbackTimeoutMs = 2000,
        )
        // xtcp 保留 fallbackTo / fallbackTimeoutMs；stcp visitor 带这些字段 frpc 会 unknown field 直接退出
        assertEquals("x-stcp", xtcp.sanitizedForType().fallbackTo)
        assertEquals(2000, xtcp.sanitizedForType().fallbackTimeoutMs)

        val stcp = xtcp.copy(type = "stcp").sanitizedForType()
        assertEquals(null, stcp.fallbackTo, "stcp 访问者必须剥掉 fallbackTo")
        assertEquals(null, stcp.fallbackTimeoutMs, "stcp 访问者必须剥掉 fallbackTimeoutMs")
    }

    fun sanitizedForType_keepsTcpRemotePort() {
        val proxy = ProxyConfig(name = "p", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6001)
        assertEquals(6001, proxy.sanitizedForType().remotePort)
    }

    @Test
    fun decode_stripsInvalidFieldsFromImportedToml() {
        // 模拟导入一份 sudp 带 remotePort 的配置（真实场景：frpc 会因此启动失败）
        val toml = """
            serverAddr = "1.2.3.4"
            serverPort = 7000

            [auth]
            method = "token"
            token = "t"

            [[proxies]]
            name = "bad-sudp"
            type = "sudp"
            localIP = "127.0.0.1"
            localPort = 53
            remotePort = 6001
        """.trimIndent()
        val config = FrpcToml.decode(toml)
        val proxy = config.proxies.single()
        assertEquals("sudp", proxy.type)
        assertEquals(null, proxy.remotePort, "导入时应清理 sudp 不支持的 remotePort")
        // 清理后校验不再报错
        val issues = ConfigValidator.validate(config)
        assertTrue(issues.none { it.reason == Reason.FIELD_NOT_SUPPORTED_FOR_TYPE })
    }

    @Test
    fun decode_tcpmuxRemotePortStripped() {
        val toml = """
            serverAddr = "1.2.3.4"

            [auth]
            token = "t"

            [[proxies]]
            name = "mux"
            type = "tcpmux"
            multiplexer = "httpconnect"
            localIP = "127.0.0.1"
            localPort = 10701
            customDomains = ["tunnel1"]
            remotePort = 6001
        """.trimIndent()
        val proxy = FrpcToml.decode(toml).proxies.single()
        assertEquals(null, proxy.remotePort)
        assertEquals("httpconnect", proxy.multiplexer)
        assertEquals(listOf("tunnel1"), proxy.customDomains)
    }

    @Test
    fun validConfigsStillPass() {
        val config = FrpcConfig(
            serverAddr = "frps.example.com",
            auth = com.frpc.shared.model.AuthConfig(token = "tok"),
            proxies = listOf(
                ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6001),
                ProxyConfig(name = "web", type = "http", localIP = "127.0.0.1", localPort = 80, subdomain = "web"),
                ProxyConfig(name = "st", type = "stcp", localIP = "127.0.0.1", localPort = 22, secretKey = "k"),
            ),
        )
        assertTrue(ConfigValidator.validate(config).isEmpty())
    }
}
