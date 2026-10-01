package org.blaze.service.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.blaze.download.api.ApiHeaders
import org.blaze.download.api.AuthMode
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.service.ApplicationDownloadService
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import org.blaze.service.security.ClientConsentGate
import org.blaze.service.security.ClientIdentity
import org.blaze.service.security.ConsentDecision
import org.blaze.service.support.FakeEngine
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the consent gate is wired into the HTTP transport: an authenticated-but-unrecognized caller is
 * held at the door until the user answers, its self-declared identity reaches the prompt, a refusal maps
 * to a 403, and an approval is cached per session so one answer covers every later request.
 */
class LocalApiServerConsentTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
    private val servers = mutableListOf<LocalApiServer>()

    @AfterTest
    fun teardown() {
        servers.forEach { it.stop() }
        job.cancel()
    }

    private class Running(val base: String, val token: String)

    /** A gate the test controls: fixed answer, recording every identity and how often it was asked. */
    private class RecordingGate(private val decision: ConsentDecision) : ClientConsentGate {
        val seen = mutableListOf<ClientIdentity>()
        val calls = AtomicInteger()
        override suspend fun decide(client: ClientIdentity): ConsentDecision {
            calls.incrementAndGet()
            synchronized(seen) { seen += client }
            return decision
        }
    }

    private fun start(gate: ClientConsentGate): Running {
        val tokenFile = Paths.get(File(System.getProperty("java.io.tmpdir"), "tok-" + System.nanoTime()).absolutePath)
        val store = ApiTokenStore(tokenFile)
        val api = ApplicationDownloadService(
            engine = FakeEngine(), scope = scope,
            settingsRepository = EngineSettingsRepository(
                Paths.get(File(System.getProperty("java.io.tmpdir"), "settings-" + System.nanoTime()).absolutePath),
            ),
            authMode = AuthMode.BEARER_TOKEN,
            eventMinIntervalMillis = 0,
        )
        val server = LocalApiServer(api, LocalApiSettings(requireAuth = true, eventMinIntervalMillis = 0), store, scope, gate)
        server.start()
        servers += server
        return Running("http://127.0.0.1:${server.boundPort}", store.token())
    }

    private fun get(r: Running, path: String, clientId: String?, clientName: String?): HttpResponse<String> {
        var b = HttpRequest.newBuilder(URI.create(r.base + path))
            .header("Authorization", "Bearer ${r.token}")
            .GET()
        if (clientId != null) b = b.header(ApiHeaders.CLIENT_ID, clientId)
        if (clientName != null) b = b.header(ApiHeaders.CLIENT_NAME, clientName)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a refused client is rejected with 403 and its identity reaches the prompt`() {
        val gate = RecordingGate(ConsentDecision.DENY)
        val r = start(gate)

        val resp = get(r, "/api/v1/downloads", clientId = "cid-9", clientName = "Evil App")

        assertEquals(403, resp.statusCode())
        assertEquals(1, gate.calls.get())
        assertEquals("cid-9", gate.seen.first().id)
        assertEquals("Evil App", gate.seen.first().displayName)
    }

    @Test
    fun `an allowed client passes and is asked only once per session`() {
        val gate = RecordingGate(ConsentDecision.ALLOW_ALWAYS)
        val r = start(gate)

        assertEquals(200, get(r, "/api/v1/downloads", clientId = "cid-a", clientName = "Friend").statusCode())
        assertEquals(200, get(r, "/api/v1/downloads", clientId = "cid-a", clientName = "Friend").statusCode())

        // The second request hit the per-session cache, so the user was not prompted again.
        assertEquals(1, gate.calls.get())
    }

    @Test
    fun `a one-time approval still lets the request through`() {
        val gate = RecordingGate(ConsentDecision.ALLOW_ONCE)
        val r = start(gate)

        assertEquals(200, get(r, "/api/v1/downloads", clientId = "cid-once", clientName = "CLI").statusCode())
        assertEquals(1, gate.calls.get())
    }

    @Test
    fun `an anonymous caller falls back to a server-derived id`() {
        val gate = RecordingGate(ConsentDecision.ALLOW_ALWAYS)
        val r = start(gate)

        val resp = http.send(
            HttpRequest.newBuilder(URI.create(r.base + "/api/v1/downloads"))
                .header("Authorization", "Bearer ${r.token}")
                .header("User-Agent", "curl/8.0")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, resp.statusCode())
        assertTrue(gate.seen.first().id.startsWith("agent:"))
    }
}
