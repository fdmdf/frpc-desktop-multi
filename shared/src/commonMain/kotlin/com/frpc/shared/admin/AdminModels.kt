package com.frpc.shared.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/status 返回的单个代理状态（字段名与 frp client/http/model 一致）。
 */
@Serializable
data class ProxyStatusItem(
    val name: String = "",
    val type: String = "",
    /** running / closed / error / new / waitStart / start 等 */
    val status: String = "",
    val err: String = "",
    @SerialName("local_addr") val localAddr: String = "",
    val plugin: String = "",
    @SerialName("remote_addr") val remoteAddr: String = "",
    val source: String = "",
)

/** 一次状态快照：按代理类型分组的全部代理 */
data class ProxyStatusSnapshot(
    val byType: Map<String, List<ProxyStatusItem>>,
    val fetchedAtEpochMs: Long,
) {
    val all: List<ProxyStatusItem> get() = byType.values.flatten()

    fun item(name: String): ProxyStatusItem? = all.firstOrNull { it.name == name }

    companion object {
        fun empty(nowEpochMs: Long): ProxyStatusSnapshot =
            ProxyStatusSnapshot(emptyMap(), nowEpochMs)
    }
}

/** 探活结果（/healthz，无需鉴权） */
sealed interface AdminHealth {
    data object Ok : AdminHealth
    data object Unreachable : AdminHealth
    data class AuthFailed(val message: String) : AdminHealth
}

/** admin API 调用失败 */
class AdminApiException(message: String, val httpCode: Int = -1) : Exception(message)
