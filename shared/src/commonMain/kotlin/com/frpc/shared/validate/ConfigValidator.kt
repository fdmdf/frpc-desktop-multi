package com.frpc.shared.validate

import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.FrpcNaming
import com.frpc.shared.model.ProxyType

enum class Severity { ERROR, WARNING }

enum class Scope { GLOBAL, AUTH, WEB_SERVER, TRANSPORT, LOG, PROXY, VISITOR }

enum class Reason {
    EMPTY_REQUIRED_FIELD,
    PORT_OUT_OF_RANGE,
    PORT_IN_USE,
    INVALID_VALUE,
    DUPLICATE_NAME,
    TYPE_FIELD_MISSING,
    DOMAIN_OR_SUBDOMAIN_REQUIRED,
    UNSUPPORTED_VALUE,
    FIELD_NOT_SUPPORTED_FOR_TYPE,

    BIND_PORT_REQUIRED,
    SUBDOMAIN_INVALID,
    MULTIPLEXER_INVALID,
    BANDWIDTH_LIMIT_INVALID,

    XTCP_STUN_SERVER_NOT_SET,
    FALLBACK_VISITOR_NOT_FOUND,

    SERVER_NAME_PREFIX_DOUBLED,
}

data class ValidationIssue(
    val severity: Severity,
    val scope: Scope,
    val field: String,
    val reason: Reason,
    val entityName: String? = null,
    val detail: String? = null,
)

object ConfigValidator {
    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    fun validate(
        config: FrpcConfig,
        portInUse: (Int) -> Boolean = { false },
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (config.serverAddr.isBlank()) {
            issues += ValidationIssue(Severity.ERROR, Scope.GLOBAL, "serverAddr", Reason.EMPTY_REQUIRED_FIELD)
        }
        checkPort(config.serverPort, "serverPort", Scope.GLOBAL, issues)

        val auth = config.auth
        val authMethod = auth?.method?.ifBlank { "none" } ?: "none"
        when (authMethod) {
            "token" -> if (auth?.token.isNullOrBlank()) {
                issues += ValidationIssue(Severity.ERROR, Scope.AUTH, "auth.token", Reason.EMPTY_REQUIRED_FIELD)
            }
            "none" -> Unit
            "oidc" -> issues += ValidationIssue(
                Severity.WARNING, Scope.AUTH, "auth.method", Reason.UNSUPPORTED_VALUE,
            )
            else -> issues += ValidationIssue(
                Severity.ERROR, Scope.AUTH, "auth.method", Reason.INVALID_VALUE,
            )
        }

        config.webServer?.let { ws ->
            if (ws.addr.isBlank()) {
                issues += ValidationIssue(Severity.ERROR, Scope.WEB_SERVER, "webServer.addr", Reason.EMPTY_REQUIRED_FIELD)
            }
            checkPort(ws.port, "webServer.port", Scope.WEB_SERVER, issues)
            if (ws.port in PORT_MIN..PORT_MAX && portInUse(ws.port)) {
                issues += ValidationIssue(Severity.ERROR, Scope.WEB_SERVER, "webServer.port", Reason.PORT_IN_USE)
            }
        }

        config.transport?.let { t ->
            if (t.protocol !in setOf("tcp", "kcp", "quic", "websocket", "wss")) {
                issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.protocol", Reason.INVALID_VALUE)
            }
            if (t.poolCount < 0) {
                issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.poolCount", Reason.INVALID_VALUE)
            }
            if (t.dialServerTimeout < 0) {
                issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.dialServerTimeout", Reason.INVALID_VALUE)
            }
            t.heartbeatInterval?.let {
                if (it <= 0) {
                    issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.heartbeatInterval", Reason.INVALID_VALUE)
                }
            }
            t.heartbeatTimeout?.let {
                if (it <= 0) {
                    issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.heartbeatTimeout", Reason.INVALID_VALUE)
                }
            }
            t.tcpMuxKeepaliveInterval?.let {
                if (it < 0) {
                    issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.tcpMuxKeepaliveInterval", Reason.INVALID_VALUE)
                }
            }
            t.tls?.let { tls ->
                val cert = tls.certFile?.takeIf { it.isNotBlank() }
                val key = tls.keyFile?.takeIf { it.isNotBlank() }
                if ((cert == null) != (key == null)) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.TRANSPORT, "transport.tls.certFile", Reason.INVALID_VALUE,
                        detail = "certFile 与 keyFile 必须同时填写",
                    )
                }
            }
        }

        config.log?.let { l ->
            if (l.level !in setOf("trace", "debug", "info", "warn", "error")) {
                issues += ValidationIssue(Severity.ERROR, Scope.LOG, "log.level", Reason.INVALID_VALUE)
            }
        }

        val names = mutableSetOf<String>()
        config.proxies.forEachIndexed { index, proxy ->
            val prefix = "proxies[$index]"
            if (proxy.name.isBlank()) {
                issues += ValidationIssue(Severity.ERROR, Scope.PROXY, "$prefix.name", Reason.EMPTY_REQUIRED_FIELD)
            } else if (!names.add(proxy.name)) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.name", Reason.DUPLICATE_NAME, proxy.name,
                )
            }
            val type = ProxyType.fromId(proxy.type)
            if (type == null) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.type", Reason.INVALID_VALUE, proxy.name,
                )
                return@forEachIndexed
            }
            if (type.needsLocalAddr) {
                if (proxy.localIP.isNullOrBlank()) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.localIP", Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                    )
                }
                if (proxy.localPort == null || proxy.localPort !in PORT_MIN..PORT_MAX) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.localPort", Reason.PORT_OUT_OF_RANGE, proxy.name,
                    )
                }
            }
            if (type.needsRemotePort && proxy.remotePort != null && proxy.remotePort !in 0..PORT_MAX) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.remotePort", Reason.PORT_OUT_OF_RANGE, proxy.name,
                )
            }
            if (type.needsSecretKey && proxy.secretKey.isNullOrBlank()) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.secretKey", Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                )
            }
            if (type.domainBased) {
                val hasDomain = !proxy.customDomains.isNullOrEmpty() || !proxy.subdomain.isNullOrBlank()
                if (!hasDomain) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.customDomains",
                        Reason.DOMAIN_OR_SUBDOMAIN_REQUIRED, proxy.name,
                    )
                }
            }
            if (type == ProxyType.TCPMUX && proxy.multiplexer.isNullOrBlank()) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.multiplexer", Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                )
            }
            if (type == ProxyType.TCPMUX && !proxy.multiplexer.isNullOrBlank() &&
                proxy.multiplexer != "httpconnect"
            ) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.multiplexer",
                    Reason.MULTIPLEXER_INVALID, proxy.name, detail = "唯一可选值 httpconnect",
                )
            }
            if (type.domainBased && !proxy.subdomain.isNullOrBlank() &&
                (proxy.subdomain.contains('.') || proxy.subdomain.contains('*'))
            ) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.subdomain",
                    Reason.SUBDOMAIN_INVALID, proxy.name, detail = "只填一级名字，例如 web01",
                )
            }
            val bandwidth = proxy.transport?.bandwidthLimit?.trim().orEmpty()
            if (bandwidth.isNotEmpty() && !isBandwidthLimit(bandwidth)) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.transport.bandwidthLimit",
                    Reason.BANDWIDTH_LIMIT_INVALID, proxy.name, detail = "例如 1MB、512KB",
                )
            }
            if (proxy.remotePort != null && !type.needsRemotePort) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.remotePort",
                    Reason.FIELD_NOT_SUPPORTED_FOR_TYPE, proxy.name,
                )
            }
            if (!type.domainBased) {
                listOf("subdomain" to proxy.subdomain, "customDomains" to proxy.customDomains?.isNotEmpty())
                    .forEach { (field, present) ->
                        if (present == true) {
                            issues += ValidationIssue(
                                Severity.ERROR, Scope.PROXY, "$prefix.$field",
                                Reason.FIELD_NOT_SUPPORTED_FOR_TYPE, proxy.name,
                            )
                        }
                    }
            }
            if (!type.needsSecretKey && (!proxy.secretKey.isNullOrBlank() || !proxy.allowUsers.isNullOrEmpty())) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.secretKey",
                    Reason.FIELD_NOT_SUPPORTED_FOR_TYPE, proxy.name,
                )
            }
            if (!type.supportsMultiplexer && !proxy.multiplexer.isNullOrBlank()) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.multiplexer",
                    Reason.FIELD_NOT_SUPPORTED_FOR_TYPE, proxy.name,
                )
            }
            val plugin = proxy.plugin
            if (plugin != null) {
                if (plugin.type.isBlank()) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.plugin.type",
                        Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                    )
                } else if (plugin.isHttpsPlugin && type != ProxyType.HTTPS) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.plugin",
                        Reason.FIELD_NOT_SUPPORTED_FOR_TYPE, proxy.name,
                        detail = "${plugin.type} 只能用于 https 代理",
                    )
                }
                if (plugin.isHttpsPlugin) {
                    if (plugin.localAddr.isNullOrBlank()) {
                        issues += ValidationIssue(
                            Severity.ERROR, Scope.PROXY, "$prefix.plugin.localAddr",
                            Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                        )
                    }
                    if (plugin.crtPath.isNullOrBlank()) {
                        issues += ValidationIssue(
                            Severity.ERROR, Scope.PROXY, "$prefix.plugin.crtPath",
                            Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                        )
                    }
                    if (plugin.keyPath.isNullOrBlank()) {
                        issues += ValidationIssue(
                            Severity.ERROR, Scope.PROXY, "$prefix.plugin.keyPath",
                            Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                        )
                    }
                }
            }
        }

        val visitorNames = config.visitors.map { it.name }.toSet()
        config.visitors.forEachIndexed { index, visitor ->
            val prefix = "visitors[$index]"
            if (visitor.name.isBlank()) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.VISITOR, "$prefix.name", Reason.EMPTY_REQUIRED_FIELD,
                )
            }
            if (visitor.type !in setOf("stcp", "xtcp", "sudp")) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.VISITOR, "$prefix.type", Reason.INVALID_VALUE, visitor.name,
                )
            }
            if (visitor.serverName.isBlank()) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.VISITOR, "$prefix.serverName", Reason.EMPTY_REQUIRED_FIELD, visitor.name,
                )
            }
            if (visitor.bindPort == 0) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.VISITOR, "$prefix.bindPort",
                    Reason.BIND_PORT_REQUIRED, visitor.name, detail = "不监听请填 -1",
                )
            }
            val fallback = visitor.fallbackTo
            if (!fallback.isNullOrBlank() && fallback !in visitorNames) {
                issues += ValidationIssue(
                    Severity.WARNING, Scope.VISITOR, "$prefix.fallbackTo",
                    Reason.FALLBACK_VISITOR_NOT_FOUND, visitor.name,
                )
            }
            val userPrefix = visitor.serverUser?.takeIf { it.isNotBlank() }
                ?: config.user?.takeIf { it.isNotBlank() }
            if (userPrefix != null && isCrossUserServerName(visitor.serverName)) {
                val actual = FrpcNaming.targetServerProxyName(
                    config.user, visitor.serverUser, visitor.serverName,
                )
                issues += ValidationIssue(
                    Severity.WARNING, Scope.VISITOR, "$prefix.serverName",
                    Reason.SERVER_NAME_PREFIX_DOUBLED, visitor.name,
                    detail = "当前会查找「$actual」",
                )
            }
        }

        val usesXtcp = config.proxies.any { it.type == "xtcp" } || config.visitors.any { it.type == "xtcp" }
        if (usesXtcp && config.natHoleStunServer.isNullOrBlank()) {
            issues += ValidationIssue(
                Severity.WARNING, Scope.GLOBAL, "natHoleStunServer", Reason.XTCP_STUN_SERVER_NOT_SET,
            )
        }

        return issues
    }

    fun hasError(issues: List<ValidationIssue>): Boolean =
        issues.any { it.severity == Severity.ERROR }

    private fun isCrossUserServerName(serverName: String): Boolean {
        val dot = serverName.indexOf('.')
        return dot > 0 && dot < serverName.length - 1
    }

    private fun isBandwidthLimit(value: String): Boolean {
        val unit = when {
            value.endsWith("MB") -> value.dropLast(2)
            value.endsWith("KB") -> value.dropLast(2)
            else -> return false
        }
        return unit.isNotEmpty() && unit.all { it.isDigit() || it == '.' } && unit.count { it == '.' } <= 1 &&
            unit.toDoubleOrNull() != null
    }

    private fun checkPort(
        port: Int,
        field: String,
        scope: Scope,
        issues: MutableList<ValidationIssue>,
    ) {
        if (port !in PORT_MIN..PORT_MAX) {
            issues += ValidationIssue(Severity.ERROR, scope, field, Reason.PORT_OUT_OF_RANGE)
        }
    }
}
