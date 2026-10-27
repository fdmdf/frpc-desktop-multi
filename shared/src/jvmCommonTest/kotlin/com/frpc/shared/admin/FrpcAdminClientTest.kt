package com.frpc.shared.admin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FrpcAdminClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private var lastMethod: String = ""
    private var lastPath: String = ""
    private var lastAuthorization: String? = null
    private var healthzCode: Int = 200

    private val statusJson = """
        {"tcp":[{"name":"ssh","type":"tcp","status":"running","err":"","local_addr":"127.0.0.1:22","plugin":"","remote_addr":"1.2.3.4:6000"}],
         "http":[{"name":"web","type":"http","status":"error","err":"port already used","local_addr":"127.0.0.1:80","plugin":"","remote_addr":""}]}
    """.trimIndent()

    @BeforeTest
    fun setup() {
        server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/healthz") { ex ->
            record(ex)
            if (healthzCode == 401 && lastAuthorization == null) {
                reply(ex, 401, "unauthorized")
            } else {
                reply(ex, 200, "ok")
            }
        }
        server.createContext("/api/status") { ex ->
            record(ex)
            reply(ex, 200, statusJson)
        }
        server.createContext("/api/reload") { ex ->
            record(ex)
            reply(ex, 200, "{}")
        }
        server.createContext("/api/stop") { ex ->
            record(ex)
            reply(ex, 200, "{}")
        }
        server.createContext("/api/config") { ex ->
            record(ex)
            reply(ex, 200, "serverAddr = \"x\"\n")
        }
        server.executor = null
        server.start()
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
    }

    @Test
    fun status_parsesProxyMap() = runBlocking {
        val client = FrpcAdminClient(port = port)
        val snapshot = client.status()
        assertEquals(2, snapshot.all.size)
        assertEquals("running", snapshot.item("ssh")?.status)
        assertEquals("1.2.3.4:6000", snapshot.item("ssh")?.remoteAddr)
        assertEquals("error", snapshot.item("web")?.status)
        assertTrue(snapshot.item("web")?.err?.contains("port already used") == true)
        assertEquals(listOf("ssh"), snapshot.byType["tcp"]?.map { it.name })
        assertEquals("GET", lastMethod)
        assertEquals("/api/status", lastPath)
    }

    @Test
    fun healthz_ok() = runBlocking {
        val client = FrpcAdminClient(port = port)
        assertEquals(AdminHealth.Ok, client.healthz())
    }

    @Test
    fun healthz_authFailed() = runBlocking {
        healthzCode = 401
        val client = FrpcAdminClient(port = port, user = "admin", password = "admin")
        // 无 Authorization 头 -> 401 -> AuthFailed
        val anonymous = FrpcAdminClient(port = port)
        val result = anonymous.healthz()
        assertTrue(result is AdminHealth.AuthFailed, "expected AuthFailed but was $result")
        assertTrue((result as AdminHealth.AuthFailed).message.contains("401"))
        // 带凭据 -> 通过
        assertEquals(AdminHealth.Ok, client.healthz())
    }

    @Test
    fun healthz_unreachable_onClosedPort() = runBlocking {
        val probe = java.net.ServerSocket(0)
        val freePort = probe.localPort
        probe.close()
        val client = FrpcAdminClient(port = freePort)
        assertEquals(AdminHealth.Unreachable, client.healthz())
    }

    @Test
    fun reload_sendsGet() = runBlocking {
        val client = FrpcAdminClient(port = port)
        assertTrue(client.reload())
        assertEquals("GET", lastMethod)
        assertEquals("/api/reload", lastPath)
    }

    @Test
    fun stop_sendsPost() = runBlocking {
        val client = FrpcAdminClient(port = port)
        assertTrue(client.stop())
        assertEquals("POST", lastMethod)
        assertEquals("/api/stop", lastPath)
    }

    @Test
    fun basicAuth_headerSent() = runBlocking {
        val client = FrpcAdminClient(port = port, user = "admin", password = "pass")
        client.reload()
        assertEquals("Basic YWRtaW46cGFzcw==", lastAuthorization)
    }

    @Test
    fun unexpectedStatus_throwsWithHttpCode() = runBlocking {
        // 另起一个对 /api/reload 固定返回 404 的服务器
        val errorServer = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        errorServer.createContext("/api/reload") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        errorServer.executor = null
        errorServer.start()
        try {
            val client = FrpcAdminClient(port = errorServer.address.port)
            val error = assertFailsWith<AdminApiException> { client.reload() }
            assertEquals(404, error.httpCode)
        } finally {
            errorServer.stop(0)
        }
    }

    @Test
    fun configToml_returnsRawToml() = runBlocking {
        val client = FrpcAdminClient(port = port)
        val toml = client.configToml()
        assertTrue(toml.contains("serverAddr"))
    }

    private fun record(ex: HttpExchange) {
        lastMethod = ex.requestMethod
        lastPath = ex.requestURI.path
        lastAuthorization = ex.requestHeaders.getFirst("Authorization")
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
