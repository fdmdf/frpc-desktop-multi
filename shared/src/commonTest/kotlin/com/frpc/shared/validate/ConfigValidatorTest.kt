package com.frpc.shared.validate

import com.frpc.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private fun baseConfig() = FrpcConfig(
        serverAddr = "frps.example.com",
        auth = AuthConfig(token = "tok"),
    )

    @Test
    fun validConfig_noIssues() {
        val config = baseConfig().copy(
            webServer = WebServerConfig(port = 7400),
            proxies = listOf(
                ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6001),
                ProxyConfig(name = "web", type = "http", localIP = "127.0.0.1", localPort = 80, subdomain = "web"),
                ProxyConfig(name = "st", type = "stcp", localIP = "127.0.0.1", localPort = 22, secretKey = "k"),
            ),
        )
        val issues = ConfigValidator.validate(config, portInUse = { false })
        assertTrue(issues.isEmpty(), "unexpected issues: $issues")
    }

    @Test
    fun xtcpWithoutStunServer_warning() {
        // xtcp 打洞要先做 STUN 探测；没填服务器就会用 frp 默认地址，超时会导致打洞失败
        val withProxy = baseConfig().copy(
            proxies = listOf(
                ProxyConfig(name = "x", type = "xtcp", localIP = "127.0.0.1", localPort = 22, secretKey = "k"),
            ),
        )
        assertTrue(
            ConfigValidator.validate(withProxy).any { it.reason == Reason.XTCP_STUN_SERVER_NOT_SET },
            "xtcp 代理未填 STUN 应给警告",
        )
        assertTrue(
            ConfigValidator.validate(withProxy.copy(natHoleStunServer = "stun.miwifi.com:3478"))
                .none { it.reason == Reason.XTCP_STUN_SERVER_NOT_SET },
            "填了 STUN 就不该再警告",
        )

        val withVisitor = baseConfig().copy(
            visitors = listOf(VisitorConfig(name = "v", type = "xtcp", serverName = "x", secretKey = "k")),
        )
        assertTrue(
            ConfigValidator.validate(withVisitor).any { it.reason == Reason.XTCP_STUN_SERVER_NOT_SET },
            "xtcp 访问者未填 STUN 同样应给警告",
        )
    }

    @Test
    fun fallbackVisitorNotFound_warning() {
        val ok = baseConfig().copy(
            visitors = listOf(
                VisitorConfig(name = "x", type = "xtcp", serverName = "p", secretKey = "k", fallbackTo = "x-stcp"),
                VisitorConfig(name = "x-stcp", type = "stcp", serverName = "p", secretKey = "k"),
            ),
        )
        assertTrue(
            ConfigValidator.validate(ok).none { it.reason == Reason.FALLBACK_VISITOR_NOT_FOUND },
            "兜底访问者存在时不该警告",
        )

        val broken = baseConfig().copy(
            visitors = listOf(
                VisitorConfig(name = "x", type = "xtcp", serverName = "p", secretKey = "k", fallbackTo = "拼错了"),
            ),
        )
        assertTrue(
            ConfigValidator.validate(broken).any { it.reason == Reason.FALLBACK_VISITOR_NOT_FOUND },
            "兜底访问者不存在应给警告",
        )
    }

    @Test
    fun serverNameWithPrefix_warnsDoublePrefix() {
        // 真实踩坑案例：全局 user=小米14，访问者目标名却写成 ThinkBook16+.testx
        // → frpc 实际查 小米14.ThinkBook16+.testx，frps 上不存在
        val config = baseConfig().copy(
            user = "小米14",
            visitors = listOf(VisitorConfig(name = "testx", type = "xtcp", serverName = "ThinkBook16+.testx", secretKey = "k")),
        )
        val hit = ConfigValidator.validate(config).first { it.reason == Reason.SERVER_NAME_PREFIX_DOUBLED }
        assertTrue(
            hit.detail?.contains("小米14.ThinkBook16+.testx") == true,
            "警告要写出实际查找的名字：${hit.detail}",
        )

        // 正确写法：目标名只有代理名，前缀走 serverUser
        val ok = config.copy(visitors = listOf(config.visitors[0].copy(serverName = "testx", serverUser = "ThinkBook16+")))
        assertTrue(
            ConfigValidator.validate(ok).none { it.reason == Reason.SERVER_NAME_PREFIX_DOUBLED },
            "正确写法不该警告",
        )
    }

    @Test
    fun missingServerAddrAndToken_errors() {
        val issues = ConfigValidator.validate(
            FrpcConfig(serverAddr = "", auth = AuthConfig(token = "")),
        )
        assertTrue(issues.any { it.scope == Scope.GLOBAL && it.reason == Reason.EMPTY_REQUIRED_FIELD })
        assertTrue(issues.any { it.scope == Scope.AUTH && it.reason == Reason.EMPTY_REQUIRED_FIELD })
        assertTrue(ConfigValidator.hasError(issues))
    }

    @Test
    fun webServerPortInUse_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(webServer = WebServerConfig(port = 7400)),
            portInUse = { it == 7400 },
        )
        assertTrue(issues.any { it.reason == Reason.PORT_IN_USE && it.field == "webServer.port" })
    }

    @Test
    fun duplicateProxyName_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6001),
                    ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6002),
                ),
            ),
        )
        assertTrue(issues.any { it.reason == Reason.DUPLICATE_NAME && it.entityName == "ssh" })
    }

    @Test
    fun httpProxyWithoutDomain_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "web", type = "http", localIP = "127.0.0.1", localPort = 80),
                ),
            ),
        )
        assertTrue(issues.any { it.reason == Reason.DOMAIN_OR_SUBDOMAIN_REQUIRED })
    }

    @Test
    fun remotePortZero_isAllowed_forRandomAssignment() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 0),
                ),
            ),
        )
        assertTrue(issues.none { it.field.contains("remotePort") })
    }

    @Test
    fun remotePortEmpty_isAllowed_forRandomAssignment() {
        // 表单里留空 → remotePort = null，此时不写进 toml，由 frps 随机分配
        val tcp = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "web", type = "tcp", localIP = "127.0.0.1", localPort = 8080),
                ),
            ),
        )
        assertTrue(tcp.none { it.field.contains("remotePort") })

        val udp = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "dns", type = "udp", localIP = "127.0.0.1", localPort = 53),
                ),
            ),
        )
        assertTrue(udp.none { it.field.contains("remotePort") })
    }

    @Test
    fun remotePortOutOfRange_stillRejected() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "web", type = "tcp", localIP = "127.0.0.1", localPort = 8080, remotePort = 70000),
                ),
            ),
        )
        assertTrue(issues.any { it.field.contains("remotePort") && it.reason == Reason.PORT_OUT_OF_RANGE })
    }

    @Test
    fun stcpWithoutSecretKey_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "st", type = "stcp", localIP = "127.0.0.1", localPort = 22),
                ),
            ),
        )
        assertTrue(issues.any { it.reason == Reason.EMPTY_REQUIRED_FIELD && it.field.endsWith("secretKey") })
    }

    @Test
    fun invalidProxyType_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(ProxyConfig(name = "x", type = "magic")),
            ),
        )
        assertTrue(issues.any { it.field.endsWith(".type") && it.reason == Reason.INVALID_VALUE })
    }

    @Test
    fun portOutOfRange_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 99999, remotePort = 6001),
                ),
            ),
        )
        assertEquals(1, issues.count { it.reason == Reason.PORT_OUT_OF_RANGE })
    }

    // ---------------------------------------------------------------- 后补字段

    @Test
    fun authNone_isValid_withoutToken() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(auth = AuthConfig(method = "none", token = "")),
        )
        assertTrue(issues.none { it.scope == Scope.AUTH }, "auth=none 不应报错：$issues")
    }

    /** frp 用负数表示「禁用保活探测」，判成错误会把合法配置拦下来 */
    @Test
    fun negativeKeepalive_isAllowed() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(transport = TransportConfig(dialServerKeepalive = -1)),
        )
        assertTrue(issues.none { it.field == "transport.dialServerTimeout" }, "$issues")
    }

    @Test
    fun heartbeatMustBePositive() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(transport = TransportConfig(heartbeatInterval = 0, heartbeatTimeout = -5)),
        )
        assertEquals(2, issues.count { it.reason == Reason.INVALID_VALUE })
    }

    @Test
    fun tlsCertWithoutKey_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(transport = TransportConfig(tls = TlsConfig(certFile = "/tmp/a.crt"))),
        )
        assertTrue(
            issues.any { it.field == "transport.tls.certFile" && it.reason == Reason.INVALID_VALUE },
            "$issues",
        )
    }

    @Test
    fun httpsPlugin_missingFields_error() {
        val pluginIssue = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(
                        name = "web", type = "https", localIP = "127.0.0.1", localPort = 8080,
                        customDomains = listOf("a.example.com"),
                        plugin = ProxyPluginConfig(type = ProxyPluginConfig.TYPE_HTTPS2HTTP),
                    ),
                ),
            ),
        )
        assertEquals(
            3,
            pluginIssue.count { it.reason == Reason.EMPTY_REQUIRED_FIELD && it.field.contains("plugin") },
            "$pluginIssue",
        )

        val ok = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(
                        name = "web", type = "https", localIP = "127.0.0.1", localPort = 8080,
                        customDomains = listOf("a.example.com"),
                        plugin = ProxyPluginConfig(
                            type = ProxyPluginConfig.TYPE_TLS2RAW,
                            localAddr = "127.0.0.1:8080",
                            crtPath = "/tmp/a.crt",
                            keyPath = "/tmp/a.key",
                        ),
                    ),
                ),
            ),
        )
        assertTrue(ok.none { it.scope == Scope.PROXY }, "$ok")
    }

    @Test
    fun httpsPluginOnTcp_error() {
        val issues = ConfigValidator.validate(
            baseConfig().copy(
                proxies = listOf(
                    ProxyConfig(
                        name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6001,
                        plugin = ProxyPluginConfig(
                            type = ProxyPluginConfig.TYPE_HTTPS2HTTP,
                            localAddr = "127.0.0.1:8080",
                            crtPath = "/tmp/a.crt",
                            keyPath = "/tmp/a.key",
                        ),
                    ),
                ),
            ),
        )
        assertTrue(
            issues.any {
                it.field.endsWith(".plugin") && it.reason == Reason.FIELD_NOT_SUPPORTED_FOR_TYPE
            },
            "$issues",
        )
    }
}
