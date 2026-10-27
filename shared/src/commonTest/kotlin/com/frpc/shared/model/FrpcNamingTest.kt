package com.frpc.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** 对齐 frp 自己的 pkg/naming/names_test.go：BuildTargetServerProxyName */
class FrpcNamingTest {

    @Test
    fun matchesFrpSemantics() {
        // ("alice","", "test") → "alice.test"   留空 = 用本机前缀
        assertEquals("alice.test", FrpcNaming.targetServerProxyName("alice", null, "test"))
        // ("alice","bob","test") → "bob.test"   填了对方前缀就用对方的
        assertEquals("bob.test", FrpcNaming.targetServerProxyName("alice", "bob", "test"))
        // 都没有前缀 → 原样
        assertEquals("test", FrpcNaming.targetServerProxyName(null, null, "test"))
    }

    @Test
    fun realWorldMixup() {
        // 实例：本机 小米14，访问者目标名误填对方完整名字 ThinkBook16+.testx
        assertEquals(
            "小米14.ThinkBook16+.testx",
            FrpcNaming.targetServerProxyName("小米14", null, "ThinkBook16+.testx"),
        )
        // 正确写法：serverUser 填对方前缀，目标名只填代理名
        assertEquals(
            "ThinkBook16+.testx",
            FrpcNaming.targetServerProxyName("小米14", "ThinkBook16+", "testx"),
        )
    }

    @Test
    fun splitPrefixedName_givesFixSuggestion() {
        // 用户把对方完整名字填进目标名时，能拆出「对方前缀 + 代理名」，直接当修复建议
        assertEquals("ThinkBook16+" to "testx", FrpcNaming.splitPrefixedName("ThinkBook16+.testx"))
        assertEquals(null, FrpcNaming.splitPrefixedName("testx"))
        assertEquals(null, FrpcNaming.splitPrefixedName(".testx"))
        assertEquals(null, FrpcNaming.splitPrefixedName("thinkbook."))
    }
}
