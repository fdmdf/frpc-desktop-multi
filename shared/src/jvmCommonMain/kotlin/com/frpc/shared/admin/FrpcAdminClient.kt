package com.frpc.shared.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class FrpcAdminClient(
    private val addr: String = "127.0.0.1",
    private val port: Int,
    private val user: String? = null,
    private val password: String? = null,
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun healthz(): AdminHealth = withContext(Dispatchers.IO) {
        try {
            request("GET", "/healthz", expectedCodes = setOf(200))
            AdminHealth.Ok
        } catch (e: AdminApiException) {
            if (e.httpCode == 401) AdminHealth.AuthFailed(e.message ?: "unauthorized")
            else AdminHealth.Unreachable
        } catch (e: Exception) {
            AdminHealth.Unreachable
        }
    }

    suspend fun status(): ProxyStatusSnapshot = withContext(Dispatchers.IO) {
        val body = request("GET", "/api/status", expectedCodes = setOf(200))
        val byType: Map<String, List<ProxyStatusItem>> =
            json.decodeFromString(body)
        ProxyStatusSnapshot(byType = byType, fetchedAtEpochMs = System.currentTimeMillis())
    }

    suspend fun reload(): Boolean = withContext(Dispatchers.IO) {
        request("GET", "/api/reload", expectedCodes = setOf(200))
        true
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        request("POST", "/api/stop", expectedCodes = setOf(200))
        true
    }

    suspend fun configToml(): String = withContext(Dispatchers.IO) {
        request("GET", "/api/config", expectedCodes = setOf(200))
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        expectedCodes: Set<Int>,
    ): String {
        val connection = (URL("http://$addr:$port$path").openConnection(java.net.Proxy.NO_PROXY)
            as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            if (!user.isNullOrBlank()) {
                val credentials = Base64.getEncoder()
                    .encodeToString("$user:${password.orEmpty()}".toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $credentials")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in expectedCodes) {
                val errorText = readError(connection)
                throw AdminApiException(
                    "$method $path -> HTTP $code ${errorText.take(200)}",
                    code,
                )
            }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readError(connection: HttpURLConnection): String = try {
        val stream: InputStream = connection.errorStream ?: return ""
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        ""
    }
}
