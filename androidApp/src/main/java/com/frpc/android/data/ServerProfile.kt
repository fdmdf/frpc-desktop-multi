package com.frpc.android.data

import com.frpc.shared.model.FrpcConfig
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: FrpcConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
) {
    val serverSummary: String
        get() = "${config.serverAddr}:${config.serverPort}"
}
