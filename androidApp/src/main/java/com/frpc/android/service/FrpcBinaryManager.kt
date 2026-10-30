package com.frpc.android.service

import android.content.Context
import java.io.File

class FrpcBinaryManager(private val context: Context) {

    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    fun deviceAbi(): String? = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }

    fun isAbiSupported(): Boolean = deviceAbi() != null

    fun binaryFile(): File =
        File(context.applicationInfo.nativeLibraryDir, LIB_NAME)

    fun isReady(): Boolean = binaryFile().let { it.exists() && it.canExecute() }

    fun ensureInstalled(): File {
        val file = binaryFile()
        if (file.exists() && file.canExecute()) return file
        if (!file.exists()) {
            throw IllegalStateException(
                "未找到 frpc 引擎（${file.absolutePath}），请重装应用",
            )
        }
        throw IllegalStateException(
            "frpc 引擎不可执行（${file.absolutePath}），请重装应用",
        )
    }

    fun bundledVersion(): String = runCatching {
        context.assets.open("$ASSET_ROOT/VERSION").bufferedReader().use { it.readText().trim() }
    }.getOrDefault("unknown")

    private companion object {
        const val LIB_NAME = "libfrpc.so"
        const val ASSET_ROOT = "frpc"
    }
}
