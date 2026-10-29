package com.frpc.shared.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 一个服务器配置档案：元信息 + frpc 配置本体。
 *
 * 两端（Android / 桌面）持久化的是同一个结构（(profiles.json），所以这个类放在
 * jvmCommonMain —— 与 shared/net、shared/runtime 一样，靠 java.util.UUID 生成 id，
 * androidMain 与 jvmMain 共用这一份。
 *
 * 注意：**改字段必须考虑磁盘上旧数据的兼容**。kotlinx.serialization 解码时对未知字段
 * 是忽略的（`ignoreUnknownKeys`），但删掉/重命名已有字段会让老档案里那一段直接丢失，
 * 加载时该条目会被判为损坏并跳过（见两端 ProfileStore/ConfigRepository 的 load）。
 */
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
