package com.frpc.shared.toml

import java.io.InputStream

/** 测试资源读取（jvmCommonTest，androidUnitTest 同样跑在 JVM 上） */
object TestFile {
    fun read(name: String): String {
        val stream: InputStream = requireNotNull(
            TestFile::class.java.classLoader?.getResourceAsStream(name),
        ) { "test resource not found: $name" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
