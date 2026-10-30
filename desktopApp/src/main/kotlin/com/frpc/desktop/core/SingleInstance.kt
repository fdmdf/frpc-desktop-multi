package com.frpc.desktop.core

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

object SingleInstance {

    private const val ACTIVATE_PORT = 47654
    private var lock: FileLock? = null
    private var lockHandle: RandomAccessFile? = null
    private var server: ServerSocket? = null

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
        }.getOrDefault(true)

        if (!first) {
            signalExisting()
            return false
        }

        runCatching {
            val socket = ServerSocket(ACTIVATE_PORT, 1, InetAddress.getByName("127.0.0.1"))
            server = socket
            Thread({
                while (!socket.isClosed) {
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
