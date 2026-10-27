package com.frpc.android.service

import android.content.Context
import java.io.File

/**
 * frpc 引擎定位。
 *
 * 引擎以 `libfrpc.so` 形式随 APK 打包在 jniLibs/<abi>/ 下，安装时由系统解压到
 * nativeLibraryDir（该位置允许 execve；Android 10+ 起应用数据目录禁止执行二进制，
 * 即 W^X 限制，见 https://developer.android.com/about/versions/10 highlights）。
 * 因此运行时无需拷贝、无需授权，直接定位执行。
 */
class FrpcBinaryManager(private val context: Context) {

    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    /** 当前设备首选 ABI */
    fun deviceAbi(): String? = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }

    fun isAbiSupported(): Boolean = deviceAbi() != null

    /** 解压后的引擎路径（nativeLibraryDir/libfrpc.so） */
    fun binaryFile(): File =
        File(context.applicationInfo.nativeLibraryDir, LIB_NAME)

    /** 引擎是否就绪（存在且可执行） */
    fun isReady(): Boolean = binaryFile().let { it.exists() && it.canExecute() }

    /**
     * 返回可执行的引擎文件；缺失时抛出带可操作说明的异常。
     * 典型缺失原因：安装时未解压 native libs（extractNativeLibs 被关闭）、ABI 不匹配。
     */
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

    /** assets 中记录的引擎版本（与 jniLibs 内二进制由同一脚本产出，仅用于展示） */
    fun bundledVersion(): String = runCatching {
        context.assets.open("$ASSET_ROOT/VERSION").bufferedReader().use { it.readText().trim() }
    }.getOrDefault("unknown")

    private companion object {
        const val LIB_NAME = "libfrpc.so"
        const val ASSET_ROOT = "frpc"
    }
}
