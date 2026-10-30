package com.frpc.shared.toml

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.akuleshov7.ktoml.decoders.TomlMainDecoder
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import com.akuleshov7.ktoml.tree.nodes.TomlTable
import com.frpc.shared.model.AuthConfig
import com.frpc.shared.model.FrpcConfig

object FrpcToml {

    private val inputConfig = TomlInputConfig(
        ignoreUnknownNames = true,
        allowEmptyToml = true,
    )
    private val outputConfig = TomlOutputConfig(
        indentation = TomlIndentation.FOUR_SPACES,
    )
    private val format = Toml(inputConfig = inputConfig, outputConfig = outputConfig)
    private val parser = TomlParser(inputConfig)

    fun encode(config: FrpcConfig): String =
        format.encodeToString(
            FrpcConfig.serializer(),
            config.copy(
                user = config.user?.trim()?.ifBlank { null },
                clientID = config.clientID?.trim()?.ifBlank { null },
                auth = config.auth?.takeIf { it.method.isNotBlank() && it.method != "none" },
                metadatas = config.metadatas?.takeIf { it.isNotEmpty() },
                proxies = config.proxies.map { it.sanitizedForType() },
                visitors = config.visitors.map { it.sanitizedForType() },
            ),
        )

    fun decode(text: String): FrpcConfig {
        val tree = parser.parseString(text)
        mergeDuplicateTables(tree)
        val config = TomlMainDecoder.decode(FrpcConfig.serializer(), tree, inputConfig)
        return config.copy(
            auth = config.auth ?: AuthConfig(method = "none", token = ""),
            proxies = config.proxies.map { it.sanitizedForType() },
            visitors = config.visitors.map { it.sanitizedForType() },
        )
    }

    private fun mergeDuplicateTables(node: TomlNode) {
        node.children.toList().forEach { mergeDuplicateTables(it) }
        node.children
            .filterIsInstance<TomlTable>()
            .groupBy { it.name }
            .filter { it.value.size > 1 }
            .forEach { (_, group) ->
                val first = group.first()
                group.drop(1).forEach { stale ->
                    stale.children.toList().forEach { child ->
                        stale.children.remove(child)
                        first.appendChild(child)
                    }
                    node.children.remove(stale)
                }
            }
    }
}
