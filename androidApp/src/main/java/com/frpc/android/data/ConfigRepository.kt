package com.frpc.android.data

import com.frpc.shared.toml.FrpcToml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 配置档案仓库：单 JSON 文件持久化（原子写：tmp + rename）。
 * 配置文件规模小（数十条），JSON 文件足够且无需引入数据库依赖，保持包体最小。
 */
class ConfigRepository(private val file: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    /** 最近一次加载时被跳过的损坏档案（名称 + 原因），用于 UI 提示 */
    private val _loadErrors = MutableStateFlow<List<String>>(emptyList())
    val loadErrors: StateFlow<List<String>> = _loadErrors.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _loadErrors.value = emptyList()
            if (!file.exists()) return@withContext
            runCatching {
                // 先按 JsonElement 解析，再逐条解码：跳过损坏条目而不是让整份配置失效
                val rawList = json.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(file.readText())
                val ok = mutableListOf<ServerProfile>()
                val bad = mutableListOf<String>()
                for ((idx, raw) in rawList.withIndex()) {
                    runCatching { json.decodeFromJsonElement(ServerProfile.serializer(), raw) }
                        .onSuccess { ok += it }
                        .onFailure {
                            val name = (raw as? kotlinx.serialization.json.JsonObject)
                                ?.get("name")?.toString()?.trim('"')
                            bad += name?.takeIf { n -> n.isNotBlank() } ?: "第 ${idx + 1} 条"
                        }
                }
                _profiles.value = ok
                if (bad.isNotEmpty()) _loadErrors.value = bad
            }.onFailure { e ->
                // 整份文件都读不出来（非法 JSON 等）：保留为一条整体错误提示
                _loadErrors.value = listOf("配置文件解析失败：${e.message ?: "未知错误"}")
            }
        }
        Unit
    }

    fun find(id: String?): ServerProfile? =
        id?.let { pid -> _profiles.value.firstOrNull { it.id == pid } }

    suspend fun upsert(profile: ServerProfile) {
        val stamped = profile.copy(updatedAt = System.currentTimeMillis())
        persist(_profiles.value.filter { it.id != profile.id } + stamped)
    }

    suspend fun delete(id: String) {
        persist(_profiles.value.filter { it.id != id })
    }

    suspend fun duplicate(id: String): ServerProfile? {
        val source = find(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} 副本",
            createdAt = now,
            updatedAt = now,
            lastUsedAt = 0L,
        )
        upsert(copy)
        return copy
    }

    suspend fun markUsed(id: String) {
        // 只更新内存态，不写盘：避免把内存旧列表写回覆盖外部修改
        _profiles.value = _profiles.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
    }

    /** 生成可直接交付 frpc 使用的 TOML 文本 */
    fun tomlOf(profile: ServerProfile): String = FrpcToml.encode(profile.config)

    /** 从 frpc.toml 文本导入（未建模字段按设计丢弃，见 README） */
    fun importToml(name: String, toml: String): ServerProfile =
        ServerProfile(name = name, config = FrpcToml.decode(toml))

    /**
     * 清空全部配置档案（对应参考项目的 resetAllConfig）。
     * 不可撤销，UI 侧必须二次确认；默认档案 id 由调用方一并清掉。
     */
    suspend fun clearAll() = persist(emptyList())

    private suspend fun persist(list: List<ServerProfile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = file.absoluteFile.parentFile ?: return@withContext
            parent.mkdirs()
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json.encodeToString<List<ServerProfile>>(list))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            _profiles.value = list
        }
        Unit
    }
}
