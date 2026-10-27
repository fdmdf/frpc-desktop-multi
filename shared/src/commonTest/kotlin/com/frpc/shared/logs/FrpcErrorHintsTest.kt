package com.frpc.shared.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrpcErrorHintsTest {

    @Test
    fun xtcpServerMissing_explainsPrefixMixup() {
        val raw = "10:16:06.825 [W] [3dc8cf8f8daea3cc] [testx] nathole precheck error: " +
            "xtcp server for [小米14.testx] doesn't exist"
        val hint = assertNotNull(FrpcErrorHints.hintFor(raw))
        assertTrue("小米14.testx" in hint, "应带上实际查找的名字：$hint")
        assertTrue("serverUser" in hint, "应提示 serverUser：$hint")
    }

    @Test
    fun stunTimeout_suggestsServer() {
        val hint = assertNotNull(
            FrpcErrorHints.hintFor("[x] nathole prepare error: discover error: wait response from stun server timeout"),
        )
        assertTrue("STUN" in hint)
    }

    @Test
    fun deadlineExceeded_pointsAtPeer() {
        val hint = assertNotNull(FrpcErrorHints.hintFor("[testx] nathole exchange info error: get natHoleRespMsg error: context deadline exceeded"))
        assertTrue("对方" in hint, "应指出根因常在对方：$hint")
    }

    @Test
    fun normalLines_haveNoHint() {
        assertNull(FrpcErrorHints.hintFor("login to server success, get run id [abc]"))
        assertNull(FrpcErrorHints.hintFor("[test] start proxy success"))
        assertEquals(null, FrpcErrorHints.hintFor("visitor added: [testx]"))
    }
}
