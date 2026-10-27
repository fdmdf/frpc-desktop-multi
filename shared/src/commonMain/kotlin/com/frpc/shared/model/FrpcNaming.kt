package com.frpc.shared.model

/**
 * frp 的代理名拼接规则（对齐 pkg/naming/names.go 的 BuildTargetServerProxyName）：
 *
 *   serverUser 非空 → serverUser.serverName
 *   serverUser 为空 → 本机 user.serverName      ← 留空不是"没前缀"，是"用本机前缀"
 *
 * 访问者里想访问别的设备，必须把**对方**的 user 填进 serverUser，serverName 只能是代理名本身。
 */
object FrpcNaming {

    fun targetServerProxyName(localUser: String?, serverUser: String?, serverName: String): String {
        val prefix = serverUser?.trim()?.takeIf { it.isNotEmpty() }
            ?: localUser?.trim()?.takeIf { it.isNotEmpty() }
        return if (prefix == null) serverName else "$prefix.$serverName"
    }

    /**
     * serverName 里带了点（用户把对方设备的完整名字填进来了）时，拆成「对方前缀 + 代理名」。
     * 例如 ThinkBook16+.testx → (ThinkBook16+, testx)，正好是应该填进 serverUser / serverName 的值。
     */
    fun splitPrefixedName(serverName: String): Pair<String, String>? {
        val name = serverName.trim()
        val dot = name.indexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return null
        return name.substring(0, dot) to name.substring(dot + 1)
    }
}
