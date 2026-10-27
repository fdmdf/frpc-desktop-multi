package com.frpc.desktop.data

import com.frpc.shared.model.FrpcConfig
import kotlinx.serialization.Serializable
import java.util.UUID

/** 一个服务器配置档案：元信息 + frpc 配置本体 */
@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: FrpcConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
) {
    val serverSummary: String get() = "${config.serverAddr}:${config.serverPort}"
}

/**
 * 全量导出格式。
 * 带 schemaVersion 是为了以后改结构时能识别旧文件；[profiles] 与磁盘上的 profiles.json 同构，
 * 因此旧版导出的裸数组也能直接导入。
 */
@Serializable
data class ProfileBundle(
    val schemaVersion: Int = 1,
    val app: String = "FrpcDash",
    val exportedAt: Long = System.currentTimeMillis(),
    val profiles: List<ServerProfile> = emptyList(),
)

/** 全量导入结果：UI 直接拿来拼提示文案 */
data class ImportSummary(
    val added: Int,
    val updated: Int,
    val skipped: List<String>,
)
