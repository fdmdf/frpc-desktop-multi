package com.frpc.desktop

import com.frpc.desktop.data.ServerProfile
import com.frpc.shared.toml.FrpcToml
import com.frpc.shared.validate.ConfigValidator
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

fun runExportToml(args: Array<String>) {
    val index = args.indexOf("--export-toml")
    val jsonPath = args.getOrNull(index + 1)
        ?: fail("usage: --export-toml <profiles.json> <outDir>", 2)
    val outDir = args.getOrNull(index + 2)
        ?: fail("缺少输出目录", 2)

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val profiles = runCatching {
        val root = json.parseToJsonElement(File(jsonPath).readText())
        val array = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject ->
                root["profiles"] as? kotlinx.serialization.json.JsonArray
                    ?: error("缺少 profiles 字段")
            else -> error("不认识的 JSON 结构")
        }
        json.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(ServerProfile.serializer()),
            array,
        )
    }.getOrElse { fail("读取档案失败：${it.message}", 3) }

    var hasError = false
    val dir = File(outDir).apply { mkdirs() }
    profiles.forEachIndexed { i, profile ->
        val toml = FrpcToml.encode(profile.config)
        val out = File(dir, "profile-$i.toml").apply { writeText(toml) }
        println("[export-toml] #$i ${profile.name} proxies=${profile.config.proxies.size} visitors=${profile.config.visitors.size} -> ${out.name}")
        val issues = ConfigValidator.validate(profile.config)
        issues.forEach { println("[export-toml]   ${it.severity} ${it.field} ${it.reason}") }
        if (ConfigValidator.hasError(issues)) hasError = true
    }
    if (hasError) fail("配置校验未通过", 4)
    exitProcess(0)
}

private fun fail(message: String, code: Int): Nothing {
    println("[export-toml] ERROR: $message")
    exitProcess(code)
}
