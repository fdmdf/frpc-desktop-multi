package com.frpc.shared.model

object FrpcNaming {

    fun targetServerProxyName(localUser: String?, serverUser: String?, serverName: String): String {
        val prefix = serverUser?.trim()?.takeIf { it.isNotEmpty() }
            ?: localUser?.trim()?.takeIf { it.isNotEmpty() }
        return if (prefix == null) serverName else "$prefix.$serverName"
    }

    fun splitPrefixedName(serverName: String): Pair<String, String>? {
        val name = serverName.trim()
        val dot = name.indexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return null
        return name.substring(0, dot) to name.substring(dot + 1)
    }
}
