package com.frpc.shared.validate

import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.FrpcNaming
import com.frpc.shared.model.ProxyType

/** 问题严重级别 */
enum class Severity { ERROR, WARNING }

/** 问题所属配置区 */
enum class Scope { GLOBAL, AUTH, WEB_SERVER, TRANSPORT, LOG, PROXY, VISITOR }

/** 问题原因（UI 层映射为本地化文案） */
enum class Reason {
    EMPTY_REQUIRED_FIELD,
    PORT_OUT_OF_RANGE,
    PORT_IN_USE,
    INVALID_VALUE,
    DUPLICATE_NAME,
    TYPE_FIELD_MISSING,
    DOMAIN_OR_SUBDOMAIN_REQUIRED,
    UNSUPPORTED_VALUE,
    /** 字段对该代理类型不适用（frp 按类型严格解码，会导致启动失败） */
    FIELD_NOT_SUPPORTED_FOR_TYPE,
    /**
     * 访问者 bindPort 为 0：frp 的 validateVisitorBaseConfig 直接报
     * "bind port is required" 并退出，整个 frpc 起不来。
     * 不监听要填负数（frp 注释：bindPort can be less than 0）。
     */
    BIND_PORT_REQUIRED,
    /** 子域名里带了 . 或 *：frps 校验 subdomain 时报 "'.' and '*' are not supported in subdomain" */
    SUBDOMAIN_INVALID,
    /** tcpmux 的 multiplexer 只接受 httpconnect，其他值 frpc 启动即报 "not support multiplexer" */
    MULTIPLEXER_INVALID,
    /** 带宽限制必须带 KB / MB 单位（frp 解析不到单位会报 "unit not support" 并退出） */
    BANDWIDTH_LIMIT_INVALID,
    /**
     * 用了 xtcp 但没指定 natHoleStunServer：frpc 会退回默认 stun.easyvoip.com:3478，
     * 该地址在国内经常超时（nathole prepare error: wait response from stun server timeout），
     * 表现为 xtcp 间歇性打洞失败、访问者侧报 context deadline exceeded。
     */
    XTCP_STUN_SERVER_NOT_SET,
    /** xtcp 访问者的 fallbackTo 指向一个不存在的访问者：打洞失败时兜底会失效（frp 不报错，只是不生效） */
    FALLBACK_VISITOR_NOT_FOUND,
    /**
     * 访问者的「目标代理名」里带了点（通常是直接把对方设备的完整名字填进来了），
     * 而 frpc 仍会在前面再加一次前缀（serverUser，留空则用本机 user）→ 实际查找的名字必然不存在。
     */
    SERVER_NAME_PREFIX_DOUBLED,
}

data class ValidationIssue(
    val severity: Severity,
    val scope: Scope,
    /** 字段路径，如 "serverAddr" / "proxies[0].remotePort" */
    val field: String,
    val reason: Reason,
    /** 代理/访问者名（PROXY/VISITOR 作用域时非空） */
    val entityName: String? = null,
    /** 附加说明，UI 直接展示（例如「实际查找 小米14.testx」） */
    val detail: String? = null,
)

/**
 * frpc 配置校验：在启动前拦截明显错误，对齐 frpc-desktop 的端口冲突预检等能力。
 * portInUse 由调用方注入（真实实现尝试绑定本地端口），保持本类纯函数可测。
 */
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

        // auth：frps 没开 token 时必须能选 none（编码时整段省略），否则这类服务器根本连不上
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

        // webServer（admin API）
        config.webServer?.let { ws ->
            if (ws.addr.isBlank()) {
                issues += ValidationIssue(Severity.ERROR, Scope.WEB_SERVER, "webServer.addr", Reason.EMPTY_REQUIRED_FIELD)
            }
            checkPort(ws.port, "webServer.port", Scope.WEB_SERVER, issues)
            if (ws.port in PORT_MIN..PORT_MAX && portInUse(ws.port)) {
                issues += ValidationIssue(Severity.ERROR, Scope.WEB_SERVER, "webServer.port", Reason.PORT_IN_USE)
            }
        }

        // transport
        config.transport?.let { t ->
            if (t.protocol !in setOf("tcp", "kcp", "quic", "websocket", "wss")) {
                issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.protocol", Reason.INVALID_VALUE)
            }
            if (t.poolCount < 0) {
                issues += ValidationIssue(Severity.ERROR, Scope.TRANSPORT, "transport.poolCount", Reason.INVALID_VALUE)
            }
            // dialServerKeepalive 允许负数：frp 用负值表示「禁用保活探测」，
            // 早先把它和 dialServerTimeout 一起判负数，会把合法配置拦下来。
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
            // TLS 证书三件套要么都填，要么都不填；只填一半 frpc 会握手失败
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

        // log
        config.log?.let { l ->
            if (l.level !in setOf("trace", "debug", "info", "warn", "error")) {
                issues += ValidationIssue(Severity.ERROR, Scope.LOG, "log.level", Reason.INVALID_VALUE)
            }
        }

        // proxies
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
            // remotePort 留空（null）或填 0 都表示交给 frps 随机分配，是合法配置；
            // 只有填了具体值且越界才算错。frp 侧 RemotePort 的零值即随机。
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
            // frp 只认 httpconnect（validation/tcpmux: "not support multiplexer: %s"）
            if (type == ProxyType.TCPMUX && !proxy.multiplexer.isNullOrBlank() &&
                proxy.multiplexer != "httpconnect"
            ) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.multiplexer",
                    Reason.MULTIPLEXER_INVALID, proxy.name, detail = "唯一可选值 httpconnect",
                )
            }
            // frps 侧校验：子域名不允许出现 . 和 *（通配由 customDomains 承担）
            if (type.domainBased && !proxy.subdomain.isNullOrBlank() &&
                (proxy.subdomain.contains('.') || proxy.subdomain.contains('*'))
            ) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.subdomain",
                    Reason.SUBDOMAIN_INVALID, proxy.name, detail = "只填一级名字，例如 web01",
                )
            }
            // 带宽限制的单位是硬性要求，写错 frpc 直接解析失败退出
            val bandwidth = proxy.transport?.bandwidthLimit?.trim().orEmpty()
            if (bandwidth.isNotEmpty() && !isBandwidthLimit(bandwidth)) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.PROXY, "$prefix.transport.bandwidthLimit",
                    Reason.BANDWIDTH_LIMIT_INVALID, proxy.name, detail = "例如 1MB、512KB",
                )
            }
            // 类型不支持的字段（frp 按类型严格解码，未知字段直接导致 frpc 启动失败）
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
            // ---- 插件 ----
            val plugin = proxy.plugin
            if (plugin != null) {
                if (plugin.type.isBlank()) {
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.plugin.type",
                        Reason.EMPTY_REQUIRED_FIELD, proxy.name,
                    )
                } else if (plugin.isHttpsPlugin && type != ProxyType.HTTPS) {
                    // https2http / tls2raw 只对 https 代理有意义，写到别的类型上 frpc 会拒绝
                    issues += ValidationIssue(
                        Severity.ERROR, Scope.PROXY, "$prefix.plugin",
                        Reason.FIELD_NOT_SUPPORTED_FOR_TYPE, proxy.name,
                        detail = "${plugin.type} 只能用于 https 代理",
                    )
                }
                if (plugin.isHttpsPlugin) {
                    // 这三个字段是 frp 的硬性要求，缺一个就起不来
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

        // visitors
        val visitorNames = config.visitors.map { it.name }.toSet()
        config.visitors.forEachIndexed { index, visitor ->
            val prefix = "visitors[$index]"
            if (visitor.name.isBlank()) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.VISITOR, "$prefix.name", Reason.EMPTY_REQUIRED_FIELD,
                )
            }
            // frp 的 visitor 支持 stcp / xtcp / sudp 三种（sudp 是 stcp 的 UDP 版本）
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
            // bindPort = 0 不是「不监听」：frp 会当作缺省值并拒绝启动（bind port is required）
            if (visitor.bindPort == 0) {
                issues += ValidationIssue(
                    Severity.ERROR, Scope.VISITOR, "$prefix.bindPort",
                    Reason.BIND_PORT_REQUIRED, visitor.name, detail = "不监听请填 -1",
                )
            }
            // 兜底访问者必须真实存在，否则打洞失败时没有任何退路（frp 侧不会报错）
            val fallback = visitor.fallbackTo
            if (!fallback.isNullOrBlank() && fallback !in visitorNames) {
                issues += ValidationIssue(
                    Severity.WARNING, Scope.VISITOR, "$prefix.fallbackTo",
                    Reason.FALLBACK_VISITOR_NOT_FOUND, visitor.name,
                )
            }
            // 目标名里自带前缀 + frpc 再拼一次 → 实际查 [prefix].[目标名]，必然不存在
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

        // xtcp 打洞必须先做 STUN 探测：没指定服务器就用 frp 默认地址，
        // 该地址超时会让「提供 xtcp 代理的一侧」无法回应，访问者侧只能干等到超时。
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

    /** 目标名看起来已经带了 user 前缀（形如 x.y）：frpc 还会再拼一次，导致查不到 */
    private fun isCrossUserServerName(serverName: String): Boolean {
        val dot = serverName.indexOf('.')
        return dot > 0 && dot < serverName.length - 1
    }

    /**
     * frp 的 BandwidthQuantity 只认「数字 + KB/MB」，没有单位会报 "unit not support"。
     * 数字部分允许小数（frp 内部用 ParseFloat）。
     */
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
