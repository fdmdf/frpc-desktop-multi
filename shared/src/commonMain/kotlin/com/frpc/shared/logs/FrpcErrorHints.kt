package com.frpc.shared.logs

/**
 * frp 原始英文错误 → 人话提示。
 *
 * frpc 的报错对普通用户几乎不可读（尤其 xtcp 打洞链路），这里把最常见的几类翻译成
 * 「哪里错了 + 去看什么」，由两端在写入原始日志后追加一条 WARN 级别的说明。
 */
object FrpcErrorHints {

    private val XTCP_MISSING = Regex("""xtcp server for \[([^]]+)] doesn't exist""")
    private val PROXY_MISSING = Regex("""proxy \[([^]]+)] not found""")

    fun hintFor(raw: String): String? {
        XTCP_MISSING.find(raw)?.let { m ->
            val name = m.groupValues[1]
            return "frps 上没有名为「$name」的 xtcp 代理：检查访问者的「对方代理名」是否写对；" +
                "跨设备访问时「对方设备前缀（serverUser）」要填对方的 user（不是自己的）"
        }
        PROXY_MISSING.find(raw)?.let { m ->
            return "frps 上没有名为「${m.groupValues[1]}」的代理：检查对方代理名与对方的 user 前缀"
        }
        return when {
            raw.contains("wait response from stun server timeout") ->
                "STUN 服务器无响应：填一个能连通的 STUN（如 stun.miwifi.com:3478）再试；对方那一侧也要能连上 STUN"
            raw.contains("discover error: not enough addresses") ->
                "STUN 只返回了 1 个地址，无法判断 NAT 类型：换一个支持双地址的公共 STUN 或自建 coturn"
            raw.contains("nathole exchange info error") || raw.contains("get natHoleRespMsg error: context deadline exceeded") ->
                "打洞超时：对方没在超时内回应，通常是对方 frpc 未运行、密钥不一致，或对方 STUN 不可用"
            raw.contains("nathole precheck error") && raw.contains("EOF") ->
                "控制连接被服务端断开（frpc 正在自动重连）；若频繁出现请检查网络或 frps 是否重启"
            raw.contains("open tunnel timeout") ->
                "打洞没建立成功（超时）：双方都在对称 NAT / 蜂窝网络时常见，可给访问者配「打洞失败转交给」走 frps 中转"
            raw.contains("xtcp local listener closed") || raw.contains("xtcp internal listener closed") ->
                "xtcp 本地监听已关闭：通常是上面的打洞失败或应用停止引起的收尾动作，不需要单独处理"
            raw.contains("token in login doesn't match") ->
                "Token 与服务端 auth.token 不一致：检查配置文件里的认证 Token（注意首尾空格）"
            raw.contains("i/o timeout") && raw.contains("login to server") ->
                "连不上 frps：检查服务器地址/端口、防火墙，以及本机是否被系统代理拦住了连接"
            else -> null
        }
    }
}
