package com.frpc.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.frpc.android.R
import com.frpc.shared.validate.Reason
import com.frpc.shared.validate.Scope
import com.frpc.shared.validate.ValidationIssue

/**
 * 校验问题 → 本地化文案。
 * 字段标签从字段路径末段映射；文案模板见 strings.xml（reason_*）。
 */
fun fieldLabelRes(field: String): Int {
    val key = field.substringAfterLast('.')
    return when (key) {
        "serverAddr" -> R.string.field_server_addr
        "serverPort" -> R.string.field_server_port
        "user" -> R.string.field_user
        "clientID" -> R.string.field_client_id
        "token" -> R.string.field_auth_token
        "method" -> R.string.field_auth_method
        "addr" -> R.string.field_admin_addr
        "port" -> R.string.field_admin_port
        "protocol" -> R.string.field_transport_protocol
        "poolCount" -> R.string.field_pool_count
        "name" -> R.string.field_proxy_name
        "type" -> R.string.field_proxy_type
        "localIP" -> R.string.field_local_ip
        "localPort" -> R.string.field_local_port
        "remotePort" -> R.string.field_remote_port
        "subdomain" -> R.string.field_subdomain
        "customDomains" -> R.string.field_custom_domains
        "locations" -> R.string.field_locations
        "httpUser" -> R.string.field_http_user
        "secretKey" -> R.string.field_secret_key
        "multiplexer" -> R.string.field_multiplexer
        "serverName" -> R.string.field_server_name
        "serverUser" -> R.string.field_visitor_server_user
        "bindAddr" -> R.string.field_bind_addr
        "bindPort" -> R.string.field_bind_port
        "bandwidthLimit" -> R.string.field_bandwidth_limit
        "proxyProtocolVersion" -> R.string.field_proxy_protocol
        "level" -> R.string.field_log_level
        "natHoleStunServer" -> R.string.field_stun_server
        "fallbackTo" -> R.string.field_fallback_to
        "fallbackTimeoutMs" -> R.string.field_fallback_timeout
        else -> R.string.unknown
    }
}

fun reasonTemplateRes(reason: Reason): Int = when (reason) {
    Reason.EMPTY_REQUIRED_FIELD -> R.string.reason_empty_required_field
    Reason.PORT_OUT_OF_RANGE -> R.string.reason_port_out_of_range
    Reason.PORT_IN_USE -> R.string.reason_port_in_use
    Reason.INVALID_VALUE -> R.string.reason_invalid_value
    Reason.DUPLICATE_NAME -> R.string.reason_duplicate_name
    Reason.DOMAIN_OR_SUBDOMAIN_REQUIRED -> R.string.reason_domain_or_subdomain_required
    Reason.UNSUPPORTED_VALUE -> R.string.reason_unsupported_value
    Reason.FIELD_NOT_SUPPORTED_FOR_TYPE -> R.string.reason_field_not_supported_for_type
    Reason.TYPE_FIELD_MISSING -> R.string.reason_type_field_missing
    Reason.BIND_PORT_REQUIRED -> R.string.reason_bind_port_required
    Reason.SUBDOMAIN_INVALID -> R.string.reason_subdomain_invalid
    Reason.MULTIPLEXER_INVALID -> R.string.reason_multiplexer_invalid
    Reason.BANDWIDTH_LIMIT_INVALID -> R.string.reason_bandwidth_limit_invalid
    Reason.XTCP_STUN_SERVER_NOT_SET -> R.string.reason_xtcp_stun_not_set
    Reason.FALLBACK_VISITOR_NOT_FOUND -> R.string.reason_fallback_visitor_not_found
    Reason.SERVER_NAME_PREFIX_DOUBLED -> R.string.reason_server_name_prefix_doubled
}

fun ValidationIssue.entityPrefix(): String =
    entityName?.let { "[$it] " } ?: ""

fun ValidationIssue.scopePrefixRes(): Int = when (scope) {
    Scope.GLOBAL -> R.string.field_server_addr
    Scope.AUTH -> R.string.field_auth_method
    Scope.WEB_SERVER -> R.string.field_admin_enabled
    Scope.TRANSPORT -> R.string.field_transport_protocol
    Scope.LOG -> R.string.field_log_level
    Scope.PROXY -> R.string.proxies_title
    Scope.VISITOR -> R.string.visitors_title
}

/** 校验问题 → 本地化文案 */
@Composable
fun validationMessage(issue: ValidationIssue): String {
    val fieldLabel = stringResource(fieldLabelRes(issue.field))
    // 重名类问题要展示「哪个名字重了」，不是「哪个字段重了」
    val arg = if (issue.reason == Reason.DUPLICATE_NAME) {
        issue.entityName ?: fieldLabel
    } else {
        fieldLabel
    }
    val base = stringResource(reasonTemplateRes(issue.reason), arg)
    return issue.detail?.let { "$base（$it）" } ?: base
}

/** 从 "proxies[0].remotePort" 这类路径中取出表单字段键（"remotePort"） */
fun ValidationIssue.formFieldKey(): String =
    field.substringAfter("].", field)
