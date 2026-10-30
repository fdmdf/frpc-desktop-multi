package com.frpc.shared.toml

import java.io.InputStream

object TestFile {
    fun read(name: String): String {
        val stream: InputStream = requireNotNull(
            TestFile::class.java.classLoader?.getResourceAsStream(name),
        ) { "test resource not found: $name" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
