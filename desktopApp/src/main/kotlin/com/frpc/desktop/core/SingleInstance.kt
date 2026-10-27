package com.frpc.desktop.core

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/**
 * 单实例守卫。
 *
 * 为什么需要：两份进程同时读写 `profiles.json`，后保存的会把先保存的整份覆盖；
 * 而且两个 frpc 会抢同一个 webServer 端口。
 *
 * 做法：对 `~/.frpcdash/app.lock` 加排他文件锁。
 * - 拿到锁 = 首个实例，顺带监听一个本地端口，供后续实例唤起窗口
 * - 拿不到锁 = 已有实例，连过去发一个字节然后退出
 *
 * 用文件锁而不是「端口占用」判据：端口可能被别的程序占着，会造成「明明没启动却打不开」。
 */
object SingleInstance {

    private const val ACTIVATE_PORT = 47654
    private var lock: FileLock? = null
    private var lockHandle: RandomAccessFile? = null
    private var server: ServerSocket? = null

    /** @return true 表示本进程是首个实例；false 表示已有实例在跑（已尝试唤起） */
    fun acquire(lockFile: File, onActivate: () -> Unit): Boolean {
        val first = runCatching {
            lockFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(lockFile, "rw")
            val fileLock = raf.channel.tryLock()
            if (fileLock == null) {
                raf.close()
                false
            } else {
                lockHandle = raf
                lock = fileLock
                true
            }
        }.getOrDefault(true) // 锁文件不可写时不要拦住用户启动

        if (!first) {
            signalExisting()
            return false
        }

        runCatching {
            val socket = ServerSocket(ACTIVATE_PORT, 1, InetAddress.getByName("127.0.0.1"))
            server = socket
            Thread({
                while (!socket.isClosed) {
                    // accept 抛异常说明 socket 已关闭（或系统资源异常），直接退出循环，别空转
                    val accepted = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        accepted.use { it.getInputStream().read() }
                        onActivate()
                    }
                }
            }, "frpcdash-single-instance").apply { isDaemon = true }.start()
        }
        return true
    }

    private fun signalExisting() {
        repeat(10) {
            val ok = runCatching {
                Socket(InetAddress.getByName("127.0.0.1"), ACTIVATE_PORT).use { socket ->
                    socket.getOutputStream().write(1)
                    socket.getOutputStream().flush()
                }
                true
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(100)
        }
    }

    fun release() {
        runCatching { server?.close() }
        server = null
        runCatching { lock?.release() }
        lock = null
        runCatching { lockHandle?.close() }
        lockHandle = null
    }
}
