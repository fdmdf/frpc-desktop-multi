package com.frpc.desktop.data

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

class ProfileStore(private val file: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _loadErrors = MutableStateFlow<List<String>>(emptyList())
    val loadErrors: StateFlow<List<String>> = _loadErrors.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _loadErrors.value = emptyList()
            if (!file.exists()) return@withContext
            runCatching {
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
        _profiles.value = _profiles.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
    }

    fun tomlOf(profile: ServerProfile): String = FrpcToml.encode(profile.config)

    fun importToml(name: String, toml: String): ServerProfile =
        ServerProfile(name = name, config = FrpcToml.decode(toml))

    fun exportAll(): String = json.encodeToString(
        ProfileBundle.serializer(),
        ProfileBundle(profiles = _profiles.value),
    )

    suspend fun importAll(text: String, replace: Boolean): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val (incoming, skipped) = parseBundle(text)
                    val base = if (replace) emptyList() else _profiles.value
                    val byId = base.associateBy { it.id }.toMutableMap()
                    val names = base.map { it.name }.toMutableSet()
                    var added = 0
                    var updated = 0
                    incoming.forEach { p ->
                        if (byId.containsKey(p.id)) {
                            byId[p.id] = p
                            updated++
                        } else {
                            var finalName = p.name.ifBlank { "导入配置" }
                            var n = 2
                            while (finalName in names) {
                                finalName = "${p.name} ($n)"
                                n++
                            }
                            names += finalName
                            byId[p.id] = p.copy(name = finalName)
                            added++
                        }
                    }
                    writeLocked(byId.values.toList())
                    ImportSummary(added = added, updated = updated, skipped = skipped)
                }
            }
        }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) { mutex.withLock { writeLocked(emptyList()) } }
    }

    private fun parseBundle(text: String): Pair<List<ServerProfile>, List<String>> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return emptyList<ServerProfile>() to listOf("文件不是合法 JSON")
        val rawProfiles: List<kotlinx.serialization.json.JsonElement> = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject ->
                (root["profiles"] as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
            else -> emptyList()
        }
        val ok = mutableListOf<ServerProfile>()
        val bad = mutableListOf<String>()
        rawProfiles.forEachIndexed { index, element ->
            runCatching { json.decodeFromJsonElement(ServerProfile.serializer(), element) }
                .onSuccess { ok += it }
                .onFailure {
                    val name = (element as? kotlinx.serialization.json.JsonObject)
                        ?.get("name")?.toString()?.trim('"')
                    bad += name?.takeIf { n -> n.isNotBlank() } ?: "第 ${index + 1} 条"
                }
        }
        return ok to bad
    }

    private suspend fun persist(list: List<ServerProfile>) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(list) }
        Unit
    }

    private fun writeLocked(list: List<ServerProfile>) {
        val parent = file.absoluteFile.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _profiles.value = list
    }
}
