package com.frpc.desktop.core

import java.io.File

/**
 * 「上次是不是正常退出」的标记文件：**存在 = 上次没走完退出流程，可能有 frpc 孤儿**。
 *
 * 为什么需要：启动时清扫孤儿 frpc 要在 Windows 上 fork 一次 `powershell` 做 CIM 查询，
 * 冷启动 1.5~4s，还得跟首屏抢 CPU / 磁盘 / 杀软扫描。而绝大多数启动上次都是正常退出的
 * （`shutdown()` 已经把子进程停干净，压根没东西可扫）—— 等于每次开机都白付这笔成本。
 *
 * 判据：`~/.frpcdash/.running`
 * - 启动时写（[markStart]），**返回「写之前它就存在吗」** —— 存在说明上一轮没删掉它；
 * - 正常退出时删（[markClean]）；
 * - 进程被强杀 / 崩溃 → 文件留着 → 下次启动判定为「脏」→ 才去扫。
 *
 * 退化情况：文件被外部（清理工具等）删掉时会误判为「干净」从而跳过扫描。
 * 可接受 —— 残留的 frpc 只占端口，用户可以手动结束，比每次启动都卡几秒划算。
 */
object CleanShutdownFlag {

    private const val FILE_NAME = ".running"

    private val file: File get() = File(AppPaths.home, FILE_NAME)

    /**
     * 启动时调用：写下本轮的标记，并告诉调用方**上一轮**是否留下了标记。
     *
     * 顺序很重要：必须先判断"是否已经存在"再写，否则本轮自己写的文件会被
     * 下一行读到，判定永远为脏。
     */
    fun markStart(): Boolean {
        val leftover = runCatching { file.exists() }.getOrDefault(false)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("pid=${ProcessHandle.current().pid()} started=${System.currentTimeMillis()}\n")
        }
        return leftover
    }

    /** 正常退出时调用：删掉标记，下次启动就可以跳过孤儿清扫 */
    fun markClean() {
        runCatching { file.delete() }
    }
}
