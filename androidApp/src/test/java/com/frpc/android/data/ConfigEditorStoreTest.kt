package com.frpc.android.data

import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.VisitorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ConfigEditorStore 回归测试。
 * 重点覆盖"从代理/访问者编辑页返回时草稿被清空"的历史 bug：
 * 屏幕重入时若草稿已属于当前编辑目标，必须跳过重复初始化。
 */
class ConfigEditorStoreTest {

    private val store = ConfigEditorStore()

    @Test
    fun commitProxy_appendsToDraft() {
        store.beginNew()
        assertTrue(store.hasDraft(null))
        store.commitProxy(
            0,
            ProxyConfig(name = "ssh", type = "tcp", localIP = "127.0.0.1", localPort = 22, remotePort = 6001),
        )
        val proxies = store.draft.value?.config?.proxies
        assertEquals(1, proxies?.size)
        assertEquals("ssh", proxies?.first()?.name)
    }

    @Test
    fun draftSurvivesReentry_regressionForWipeOnReturn() {
        // 模拟：进入编辑页 → 填服务器字段 → 添加代理 → 从子页返回（屏幕重入）
        store.beginNew()
        store.update { it.copy(serverAddr = "frps.example.com") }
        store.commitProxy(
            0,
            ProxyConfig(name = "web", type = "tcp", localIP = "127.0.0.1", localPort = 80, remotePort = 6002),
        )
        // 重入守卫：草稿已属于当前配置，不得重新初始化
        assertTrue(store.hasDraft(null))
        assertEquals("frps.example.com", store.draft.value?.config?.serverAddr)
        assertEquals(1, store.draft.value?.config?.proxies?.size)
        assertEquals("web", store.draft.value?.config?.proxies?.first()?.name)
    }

    @Test
    fun hasDraft_falseForDifferentProfile() {
        store.beginNew()
        assertFalse(store.hasDraft("other-id"))
        assertTrue(store.hasDraft(null))
    }

    @Test
    fun commitProxy_replacesAtIndex() {
        store.beginNew()
        store.commitProxy(0, ProxyConfig(name = "a", type = "tcp"))
        store.commitProxy(1, ProxyConfig(name = "b", type = "tcp"))
        store.commitProxy(0, ProxyConfig(name = "a2", type = "tcp"))
        val proxies = store.draft.value?.config?.proxies
        assertEquals(2, proxies?.size)
        assertEquals("a2", proxies?.get(0)?.name)
        assertEquals("b", proxies?.get(1)?.name)
    }

    @Test
    fun proxyDraftGuard_preventsCrossTalkBetweenIndexes() {
        store.beginNew()
        store.beginProxyEdit(0, null)
        assertTrue(store.hasProxyDraft(0))
        assertFalse(store.hasProxyDraft(1))
        store.updateProxy { it.copy(name = "x") }
        assertEquals("x", store.proxyDraft.value?.name)
        store.clearProxy()
        assertFalse(store.hasProxyDraft(0))
        assertNull(store.proxyDraft.value)
    }

    @Test
    fun beginProxyEdit_newProxyHasLocalIPDefault() {
        store.beginNew()
        store.beginProxyEdit(0, null)
        assertEquals("127.0.0.1", store.proxyDraft.value?.localIP)
        assertEquals("tcp", store.proxyDraft.value?.type)
    }

    @Test
    fun commitVisitor_appendsToDraft() {
        store.beginNew()
        store.beginVisitorEdit(0, null)
        assertTrue(store.hasVisitorDraft(0))
        store.updateVisitor { it.copy(name = "v1", serverName = "secret_tcp") }
        store.commitVisitor(0, store.visitorDraft.value!!)
        val visitors = store.draft.value?.config?.visitors
        assertEquals(1, visitors?.size)
        assertEquals("v1", visitors?.first()?.name)
        assertFalse(store.hasVisitorDraft(0))
    }

    @Test
    fun clear_resetsDraft() {
        store.beginNew()
        store.commitProxy(0, ProxyConfig(name = "ssh", type = "tcp"))
        store.clear()
        assertNull(store.draft.value)
        assertFalse(store.hasDraft(null))
    }

    /**
     * 未保存就返回必须真丢掉：改过 → isDirty() 为真（弹确认）→ clearXxx 后草稿消失。
     * 屏幕重入时 `hasProxyDraft` 为真才跳过初始化，所以「返回不清草稿」会让
     * 上一次没保存的修改一直显示在编辑页里（用户报的 bug）。
     */
    @Test
    fun proxyDraft_dirtyOnlyAfterEdit_andDiscardedOnClear() {
        store.beginNew()
        val existing = ProxyConfig(name = "web", type = "tcp", localIP = "127.0.0.1", localPort = 80)
        store.beginProxyEdit(0, existing)
        assertFalse(store.isProxyDirty(), "刚进编辑页不该算改过")

        store.updateProxy { it.copy(localPort = 8080) }
        assertTrue(store.isProxyDirty())

        store.clearProxy()
        assertFalse(store.hasProxyDraft(0))
        assertNull(store.proxyDraft.value)
        assertFalse(store.isProxyDirty())
    }

    @Test
    fun visitorDraft_dirtyOnlyAfterEdit_andDiscardedOnClear() {
        store.beginNew()
        store.beginVisitorEdit(0, VisitorConfig(name = "v", type = "stcp", serverName = "secret"))
        assertFalse(store.isVisitorDirty())
        store.updateVisitor { it.copy(serverName = "secret2") }
        assertTrue(store.isVisitorDirty())
        store.clearVisitor()
        assertFalse(store.hasVisitorDraft(0))
        assertNull(store.visitorDraft.value)
    }

    @Test
    fun newConfigDraft_notDirtyUntilTyped() {
        store.beginNew()
        assertFalse(store.isConfigDirty(), "新建的空草稿不该算改过")
        store.update { it.copy(serverAddr = "frps.example.com") }
        assertTrue(store.isConfigDirty())
        store.clear()
        assertFalse(store.isConfigDirty())
    }

    /** 离开编辑栈的兜底：草稿、代理草稿、访问者草稿一并清空 */
    @Test
    fun clearAll_wipesEverything() {
        store.beginNew()
        store.update { it.copy(serverAddr = "frps.example.com") }
        store.beginProxyEdit(0, null)
        store.updateProxy { it.copy(name = "ssh") }
        store.beginVisitorEdit(0, null)
        store.updateVisitor { it.copy(name = "v1") }

        store.clearAll()

        assertNull(store.draft.value)
        assertNull(store.proxyDraft.value)
        assertNull(store.visitorDraft.value)
        assertFalse(store.hasDraft(null))
        assertFalse(store.hasProxyDraft(0))
        assertFalse(store.hasVisitorDraft(0))
        assertFalse(store.isConfigDirty())
        assertFalse(store.isProxyDirty())
        assertFalse(store.isVisitorDirty())
    }
}
