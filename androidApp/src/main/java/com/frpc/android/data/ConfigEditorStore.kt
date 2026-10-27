package com.frpc.android.data

import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.VisitorConfig
import com.frpc.shared.validate.ConfigValidator
import com.frpc.shared.validate.ValidationIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 配置编辑器草稿（Activity 级单例，跨旋转保存未保存的编辑）。
 * 同一时刻只有一个编辑流程，避免无 Navigation-Compose 时的 ViewModel key 管理复杂度。
 *
 * 防重入守卫：编辑屏幕从子页（代理/访问者编辑）返回时会重新进入组合，
 * 若此时重新 beginNew/beginEdit 会把整个草稿清空（历史上的丢数据 bug）。
 * hasDraft / hasProxyDraft / hasVisitorDraft 供屏幕判断"草稿是否已属于当前编辑目标"。
 */
class ConfigEditorStore {

    data class Draft(
        val profileId: String?,
        val name: String,
        val config: FrpcConfig,
        val createdAt: Long = System.currentTimeMillis(),
        val lastUsedAt: Long = 0L,
    ) {
        val isNew: Boolean get() = profileId == null
    }

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()

    private val _proxyDraft = MutableStateFlow<ProxyConfig?>(null)
    val proxyDraft: StateFlow<ProxyConfig?> = _proxyDraft.asStateFlow()

    private val _visitorDraft = MutableStateFlow<VisitorConfig?>(null)
    val visitorDraft: StateFlow<VisitorConfig?> = _visitorDraft.asStateFlow()

    /** 代理草稿对应的列表下标（null 表示无草稿），用于防止编辑 B 时显示 A 的草稿 */
    private var proxyDraftIndex: Int? = null

    /** 访问者草稿对应的列表下标 */
    private var visitorDraftIndex: Int? = null

    // 进入编辑时的原始值快照：返回时据此判断是否真的改过（改过才值得提示"放弃修改"）
    private var proxyOriginal: ProxyConfig? = null
    private var visitorOriginal: VisitorConfig? = null
    private var configOriginal: FrpcConfig? = null

    // ------------------------------------------------------------ 服务器配置

    fun beginNew() {
        val config = FrpcConfig(auth = com.frpc.shared.model.AuthConfig())
        configOriginal = config
        _draft.value = Draft(profileId = null, name = "", config = config)
    }

    fun beginEdit(profile: ServerProfile) {
        configOriginal = profile.config
        _draft.value = Draft(
            profileId = profile.id,
            name = profile.name,
            config = profile.config,
            createdAt = profile.createdAt,
            lastUsedAt = profile.lastUsedAt,
        )
    }

    /** 服务器配置草稿是否被改过（新建时只填了默认值的空草稿不算） */
    fun isConfigDirty(): Boolean = _draft.value?.let { it.config != configOriginal } ?: false

    /** 草稿是否已属于目标配置（屏幕重入时据此跳过重复初始化） */
    fun hasDraft(profileId: String?): Boolean =
        _draft.value != null && _draft.value?.profileId == profileId

    fun rename(name: String) {
        _draft.value = _draft.value?.copy(name = name)
    }

    fun update(transform: (FrpcConfig) -> FrpcConfig) {
        _draft.value = _draft.value?.let { it.copy(config = transform(it.config)) }
    }

    fun clear() {
        _draft.value = null
        configOriginal = null
    }

    /** 离开编辑页（返回列表 / 切 Tab）时的兜底：草稿与子草稿一并丢弃 */
    fun clearAll() {
        clear()
        clearProxy()
        clearVisitor()
    }

    /** 构建待保存的档案（保留原 id 与创建/使用时间，新建则生成新 id） */
    fun toProfile(): ServerProfile? {
        val draft = _draft.value ?: return null
        val now = System.currentTimeMillis()
        return ServerProfile(
            id = draft.profileId ?: UUID.randomUUID().toString(),
            name = draft.name,
            config = draft.config,
            createdAt = draft.createdAt,
            updatedAt = now,
            lastUsedAt = draft.lastUsedAt,
        )
    }

    fun validate(portInUse: (Int) -> Boolean): List<ValidationIssue> {
        val config = _draft.value?.config ?: return emptyList()
        return ConfigValidator.validate(config, portInUse)
    }

    // ------------------------------------------------------------ 代理草稿

    fun beginProxyEdit(index: Int, proxy: ProxyConfig?) {
        proxyDraftIndex = index
        val base = proxy ?: ProxyConfig(name = "", type = "tcp", localIP = "127.0.0.1")
        proxyOriginal = base
        _proxyDraft.value = base
    }

    /** 下标 index 的代理草稿是否已存在（屏幕重入/旋转时跳过重复初始化） */
    fun hasProxyDraft(index: Int): Boolean =
        proxyDraftIndex == index && _proxyDraft.value != null

    /** 代理草稿是否被改过（新建时只带默认值的草稿不算） */
    fun isProxyDirty(): Boolean = _proxyDraft.value?.let { it != proxyOriginal } ?: false

    fun updateProxy(transform: (ProxyConfig) -> ProxyConfig) {
        _proxyDraft.value = _proxyDraft.value?.let { transform(it) }
    }

    fun clearProxy() {
        _proxyDraft.value = null
        proxyDraftIndex = null
    }

    /** 保存代理到配置草稿：index 处替换，越界则追加 */
    fun commitProxy(index: Int, proxy: ProxyConfig) {
        update { config ->
            val list = config.proxies.toMutableList()
            if (index in list.indices) list[index] = proxy else list += proxy
            config.copy(proxies = list)
        }
        clearProxy()
    }

    // ------------------------------------------------------------ 访问者草稿

    fun beginVisitorEdit(index: Int, visitor: VisitorConfig?) {
        visitorDraftIndex = index
        val base = visitor ?: VisitorConfig(name = "", type = "stcp", serverName = "")
        visitorOriginal = base
        _visitorDraft.value = base
    }

    /** 下标 index 的访问者草稿是否已存在 */
    fun hasVisitorDraft(index: Int): Boolean =
        visitorDraftIndex == index && _visitorDraft.value != null

    /** 访问者草稿是否被改过 */
    fun isVisitorDirty(): Boolean = _visitorDraft.value?.let { it != visitorOriginal } ?: false

    fun updateVisitor(transform: (VisitorConfig) -> VisitorConfig) {
        _visitorDraft.value = _visitorDraft.value?.let { transform(it) }
    }

    fun clearVisitor() {
        _visitorDraft.value = null
        visitorDraftIndex = null
    }

    fun commitVisitor(index: Int, visitor: VisitorConfig) {
        update { config ->
            val list = config.visitors.toMutableList()
            if (index in list.indices) list[index] = visitor else list += visitor
            config.copy(visitors = list)
        }
        clearVisitor()
    }
}
