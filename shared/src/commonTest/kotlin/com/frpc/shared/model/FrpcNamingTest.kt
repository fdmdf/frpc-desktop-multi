package com.frpc.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FrpcNamingTest {

    @Test
    fun matchesFrpSemantics() {
        assertEquals("alice.test", FrpcNaming.targetServerProxyName("alice", null, "test"))
        assertEquals("bob.test", FrpcNaming.targetServerProxyName("alice", "bob", "test"))
        assertEquals("test", FrpcNaming.targetServerProxyName(null, null, "test"))
    }

    @Test
    fun realWorldMixup() {
        assertEquals(
            "小米14.ThinkBook16+.testx",
            FrpcNaming.targetServerProxyName("小米14", null, "ThinkBook16+.testx"),
        )
        assertEquals(
            "ThinkBook16+.testx",
            FrpcNaming.targetServerProxyName("小米14", "ThinkBook16+", "testx"),
        )
    }

    @Test
    fun splitPrefixedName_givesFixSuggestion() {
        assertEquals("ThinkBook16+" to "testx", FrpcNaming.splitPrefixedName("ThinkBook16+.testx"))
        assertEquals(null, FrpcNaming.splitPrefixedName("testx"))
        assertEquals(null, FrpcNaming.splitPrefixedName(".testx"))
        assertEquals(null, FrpcNaming.splitPrefixedName("thinkbook."))
    }
}
