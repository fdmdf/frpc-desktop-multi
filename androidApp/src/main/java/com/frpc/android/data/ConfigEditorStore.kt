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

    private var proxyDraftIndex: Int? = null

    private var visitorDraftIndex: Int? = null

    private var proxyOriginal: ProxyConfig? = null
    private var visitorOriginal: VisitorConfig? = null
    private var configOriginal: FrpcConfig? = null

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

    fun isConfigDirty(): Boolean = _draft.value?.let { it.config != configOriginal } ?: false

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

    fun clearAll() {
        clear()
        clearProxy()
        clearVisitor()
    }

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

    fun beginProxyEdit(index: Int, proxy: ProxyConfig?) {
        proxyDraftIndex = index
        val base = proxy ?: ProxyConfig(name = "", type = "tcp", localIP = "127.0.0.1")
        proxyOriginal = base
        _proxyDraft.value = base
    }

    fun hasProxyDraft(index: Int): Boolean =
        proxyDraftIndex == index && _proxyDraft.value != null

    fun isProxyDirty(): Boolean = _proxyDraft.value?.let { it != proxyOriginal } ?: false

    fun updateProxy(transform: (ProxyConfig) -> ProxyConfig) {
        _proxyDraft.value = _proxyDraft.value?.let { transform(it) }
    }

    fun clearProxy() {
        _proxyDraft.value = null
        proxyDraftIndex = null
    }

    fun commitProxy(index: Int, proxy: ProxyConfig) {
        update { config ->
            val list = config.proxies.toMutableList()
            if (index in list.indices) list[index] = proxy else list += proxy
            config.copy(proxies = list)
        }
        clearProxy()
    }

    fun beginVisitorEdit(index: Int, visitor: VisitorConfig?) {
        visitorDraftIndex = index
        val base = visitor ?: VisitorConfig(name = "", type = "stcp", serverName = "")
        visitorOriginal = base
        _visitorDraft.value = base
    }

    fun hasVisitorDraft(index: Int): Boolean =
        visitorDraftIndex == index && _visitorDraft.value != null

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
