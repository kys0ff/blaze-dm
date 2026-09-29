package org.blaze.service.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.blaze.download.api.ApiVersion
import org.blaze.download.api.AuthMode
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.service.ApplicationDownloadService
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import org.blaze.service.support.FakeEngine
import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the loopback HTTP transport and every security gate that guards it.
 *
 * A real [LocalApiServer] is bound on an ephemeral port atop a [FakeEngine]-backed service, and requests
 * are made with the JDK HTTP client (plus one raw socket, to send a `Host:` header the managed client
 * forbids). This is where authentication, origin/DNS-rebinding rejection, body caps, method routing,
 * error mapping and idempotency are proven to behave - before a byte ever reaches the engine.
 */
class LocalApiServerTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
    private val servers = mutableListOf<LocalApiServer>()

    @AfterTest
    fun teardown() {
        servers.forEach { it.stop() }
        job.cancel()
    }

    private class Running(val base: String, val port: Int, val token: String, val api: ApplicationDownloadService)

    private fun start(
        engine: FakeEngine = FakeEngine(),
        settings: LocalApiSettings = LocalApiSettings(requireAuth = true, eventMinIntervalMillis = 0),
    ): Running {
        val tokenFile = Paths.get(File(System.getProperty("java.io.tmpdir"), "tok-" + System.nanoTime()).absolutePath)
        val store = ApiTokenStore(tokenFile)
        val api = ApplicationDownloadService(
            engine = engine, scope = scope,
            settingsRepository = EngineSettingsRepository(Paths.get(File(System.getProperty("java.io.tmpdir"), "settings-" + System.nanoTime()).absolutePath)),
            authMode = if (settings.requireAuth) AuthMode.BEARER_TOKEN else AuthMode.NONE,
            eventMinIntervalMillis = settings.eventMinIntervalMillis,
        )
        val server = LocalApiServer(api, settings, store, scope)
        server.start()
        servers += server
        return Running("http://127.0.0.1:${server.boundPort}", server.boundPort, store.token(), api)
    }

    private fun get(r: Running, path: String, token: String? = r.token, origin: String? = null): HttpResponse<String> {
        var b = HttpRequest.newBuilder(URI.create(r.base + path)).GET()
        if (token != null) b = b.header("Authorization", "Bearer $token")
        if (origin != null) b = b.header("Origin", origin)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(r: Running, path: String, body: String?, token: String? = r.token): HttpResponse<String> {
        val pub = body?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
            ?: HttpRequest.BodyPublishers.noBody()
        var b = HttpRequest.newBuilder(URI.create(r.base + path))
            .header("Content-Type", "application/json")
            .POST(pub)
        if (token != null) b = b.header("Authorization", "Bearer $token")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun createBody(url: String, dest: String, key: String? = null) =
        """{"url":"$url","destination":"$dest","filename":"f.bin"${key?.let { ",\"idempotencyKey\":\"$it\"" } ?: ""}}"""

    private fun tmpDest(): String = File(System.getProperty("java.io.tmpdir"), "api-" + System.nanoTime())
        .apply { mkdirs() }.absolutePath

    @Test
    fun `health is public and reports the api version`() {
        val r = start()
        val resp = get(r, "/api/v1/health", token = null)
        assertEquals(200, resp.statusCode())
        assertTrue("\"apiVersion\":${ApiVersion.CURRENT}" in resp.body())
    }

    @Test
    fun `capabilities are public`() {
        val r = start()
        val resp = get(r, "/api/v1/capabilities", token = null)
        assertEquals(200, resp.statusCode())
        assertTrue("\"supportsTorrent\":true" in resp.body())
        assertTrue("\"authentication\":\"BEARER_TOKEN\"" in resp.body())
    }

    @Test
    fun `listing without a token is rejected with 401`() {
        val r = start()
        assertEquals(401, get(r, "/api/v1/downloads", token = null).statusCode())
        assertEquals(401, get(r, "/api/v1/downloads", token = "wrong").statusCode())
    }

    @Test
    fun `a valid token lists downloads`() {
        val r = start()
        assertEquals(200, get(r, "/api/v1/downloads").statusCode())
    }

    @Test
    fun `create returns 201 with a client-safe dto`() {
        val r = start()
        val resp = post(r, "/api/v1/downloads", createBody("https://example.com/a.bin", tmpDest()))
        assertEquals(201, resp.statusCode())
        assertTrue("\"state\":" in resp.body())
        assertTrue("\"source\":{" in resp.body())
    }

    @Test
    fun `malformed json is a 400 invalid request`() {
        val r = start()
        val resp = post(r, "/api/v1/downloads", "{not json")
        assertEquals(400, resp.statusCode())
        assertTrue("\"INVALID_REQUEST\"" in resp.body())
    }

    @Test
    fun `oversized body is rejected with 413 before parsing`() {
        val r = start(settings = LocalApiSettings(requireAuth = true, maxRequestBytes = 64, eventMinIntervalMillis = 0))
        val big = """{"url":"https://example.com/x","destination":"/tmp","headers":{"pad":"${"A".repeat(5000)}"}}"""
        assertEquals(413, post(r, "/api/v1/downloads", big).statusCode())
    }

    @Test
    fun `a non-allow-listed browser origin is refused`() {
        val r = start(settings = LocalApiSettings(requireAuth = true, allowedOrigins = listOf("http://good.app"), eventMinIntervalMillis = 0))
        assertEquals(403, get(r, "/api/v1/downloads", origin = "http://evil.app").statusCode())
        // The allow-listed origin still authenticates normally.
        assertEquals(200, get(r, "/api/v1/downloads", origin = "http://good.app").statusCode())
    }

    @Test
    fun `a non-loopback Host header is refused (dns-rebinding guard)`() {
        val r = start()
        val raw = "GET /api/v1/health HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n"
        val body = Socket("127.0.0.1", r.port).use { s ->
            s.getOutputStream().write(raw.toByteArray(StandardCharsets.UTF_8))
            s.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
        assertTrue(body.startsWith("HTTP/1.1 403"), "expected 403 for a rebinding Host, got: ${body.lineSequence().first()}")
    }

    @Test
    fun `lifecycle verbs and delete are routed`() {
        val r = start()
        val id = Regex("\"id\":\"([^\"]+)\"").find(post(r, "/api/v1/downloads", createBody("https://example.com/l.bin", tmpDest())).body())!!
            .groupValues[1]
        assertEquals(200, post(r, "/api/v1/downloads/$id/pause", null).statusCode())
        assertEquals(200, post(r, "/api/v1/downloads/$id/resume", null).statusCode())
        assertEquals(200, post(r, "/api/v1/downloads/$id/cancel", null).statusCode())
        assertEquals(200, http.send(
            HttpRequest.newBuilder(URI.create("${r.base}/api/v1/downloads/$id")).DELETE().header("Authorization", "Bearer ${r.token}").build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode())
    }

    @Test
    fun `unknown action and bad path map to stable errors`() {
        val r = start()
        assertEquals(400, post(r, "/api/v1/downloads/xyz/teleport", null).statusCode())
        assertEquals(404, get(r, "/api/v2/downloads").statusCode())
        assertEquals(404, get(r, "/notapi").statusCode())
    }

    @Test
    fun `idempotency-key header deduplicates retried creates`() {
        val r = start()
        val body = createBody("https://example.com/idem.bin", tmpDest())
        val first = http.send(
            HttpRequest.newBuilder(URI.create("${r.base}/api/v1/downloads"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${r.token}")
                .header("Idempotency-Key", "SAME")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val second = http.send(
            HttpRequest.newBuilder(URI.create("${r.base}/api/v1/downloads"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${r.token}")
                .header("Idempotency-Key", "SAME")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val idOf = { s: String -> Regex("\"id\":\"([^\"]+)\"").find(s)!!.groupValues[1] }
        assertEquals(idOf(first.body()), idOf(second.body()))
    }

    @Test
    fun `a create with an out-of-root destination is confined to 403`() {
        val allowed = tmpDest()
        val r = start(settings = LocalApiSettings(requireAuth = true, allowedDownloadRoots = listOf(allowed), eventMinIntervalMillis = 0))
        val outside = File(System.getProperty("user.home"), "blaze-evil").absolutePath
        assertEquals(403, post(r, "/api/v1/downloads", createBody("https://example.com/x.bin", outside)).statusCode())
    }
}
