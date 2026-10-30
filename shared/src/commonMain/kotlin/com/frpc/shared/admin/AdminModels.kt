package com.frpc.shared.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProxyStatusItem(
    val name: String = "",
    val type: String = "",
    val status: String = "",
    val err: String = "",
    @SerialName("local_addr") val localAddr: String = "",
    val plugin: String = "",
    @SerialName("remote_addr") val remoteAddr: String = "",
    val source: String = "",
)

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

sealed interface AdminHealth {
    data object Ok : AdminHealth
    data object Unreachable : AdminHealth
    data class AuthFailed(val message: String) : AdminHealth
}

class AdminApiException(message: String, val httpCode: Int = -1) : Exception(message)
