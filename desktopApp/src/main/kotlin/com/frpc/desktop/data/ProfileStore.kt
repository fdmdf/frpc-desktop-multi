package com.frpc.desktop.data

import com.frpc.shared.model.ServerProfile
import com.frpc.shared.toml.FrpcToml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 配置档案仓库：单 JSON 文件 + 原子写（tmp → rename）。
 * 桌面端配置规模很小，不引入数据库，保持零额外依赖。
 */
class ProfileStore(private val file: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    /** 最近一次加载时被跳过的损坏档案，用于 UI 提示 */
    private val _loadErrors = MutableStateFlow<List<String>>(emptyList())
    val loadErrors: StateFlow<List<String>> = _loadErrors.asStateFlow()

    /**
     * [load] 是否已经跑完（不管成功失败）。
     *
     * 存在的意义：开机自启的「自动连接」以前是 `delay(500)` 之后直接取列表，
     * 配置多 / 磁盘慢时 500ms 到了列表还是空的 ⇒ 默认档案找不到 ⇒
     * **自动连接静默失败**（既不启动也不报错）。现在改成等这个信号（带超时兜底）。
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _loadErrors.value = emptyList()
            if (!file.exists()) {
                _loaded.value = true
                return@withContext
            }
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
            // 解析失败也算「加载完成」：等的一方不该被永久挂住，
            // 它拿到空列表走的是"没有可连的配置"这条正常分支，而不是静默失败
            _loaded.value = true
        }
    }

    /**
     * 等首次 [load] 完成；已经完成过就立即返回。
     *
     * @param timeoutMs 兜底超时：磁盘被占住 / 文件锁死时不能把自动连接永久挂住，
     *   超时也返回（调用方按当前列表处理，最差是"没配置可连"而不是永远不连）。
     */
    suspend fun awaitLoaded(timeoutMs: Long = 5_000L) {
        if (_loaded.value) return
        withTimeoutOrNull(timeoutMs) {
            _loaded.first { it }
        }
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
        // 只更新内存态，不写盘：lastUsedAt 是辅助信息，
        // 若在这里 persist 会把内存旧列表整份写回，覆盖运行期间的外部修改
        _profiles.value = _profiles.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
    }

    /** 生成标准 frpc.toml 文本 */
    fun tomlOf(profile: ServerProfile): String = FrpcToml.encode(profile.config)

    /** 从 frpc.toml 导入（未建模字段按设计丢弃） */
    fun importToml(name: String, toml: String): ServerProfile =
        ServerProfile(name = name, config = FrpcToml.decode(toml))

    // ------------------------------------------------------------ 全量备份/恢复

    /** 全量导出：带 schemaVersion 的包装，和磁盘格式同构 */
    fun exportAll(): String = json.encodeToString(
        ProfileBundle.serializer(),
        ProfileBundle(profiles = _profiles.value),
    )

    /**
     * 全量导入。
     * - [replace] = true 时先清空再导入（换机迁移）；false 时按 id 合并（同 id 覆盖）
     * - 重名的不同档案自动加「(2)」后缀，避免列表里两个同名配置分不清
     * - 坏条目跳过并回报名字，不让一条坏数据毁掉整次导入
     */
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

    /** 一键清空（调用方负责先停掉正在跑的 frpc） */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) { mutex.withLock { writeLocked(emptyList()) } }
    }

    private fun parseBundle(text: String): Pair<List<ServerProfile>, List<String>> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return emptyList<ServerProfile>() to listOf("文件不是合法 JSON")
        // 兼容两种格式：ProfileBundle 包装对象、以及旧版直接导出的数组
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

    /** 已持锁时的落盘实现；不要在内部再次获取 mutex（Mutex 不可重入） */
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
