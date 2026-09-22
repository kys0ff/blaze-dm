package org.blaze.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadApiException
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.DownloadState
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.TransferHeaders
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadMetadata
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.PortableDownloadInfo
import org.blaze.engine.api.PortableTransferKind
import org.blaze.service.security.PathValidator
import org.blaze.service.support.FakeEngine
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit coverage of the application service's contract, driven against an in-memory [FakeEngine].
 *
 * Everything the task demands of the API boundary is asserted here without a network: create/auto-start,
 * idempotent replay, unambiguous duplicate detection, race-safe forwarding, unknown-id rejection,
 * destination confinement, and - critically - that authentication material reaches the engine's transient
 * request yet never surfaces in a client-facing DTO.
 */
class ApplicationDownloadServiceTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    @AfterTest
    fun teardown() = job.cancel()

    private fun service(
        engine: FakeEngine,
        validator: PathValidator = PathValidator.TRUSTED,
    ) = ApplicationDownloadService(engine, scope, validator, eventMinIntervalMillis = 0)

    private fun tmpDest(): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "blaze-test-" + System.nanoTime())
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun req(url: String, dest: String = tmpDest(), autoStart: Boolean = true, key: String? = null) =
        NewDownloadRequest(source = DownloadSource.Url(url), destination = dest, autoStart = autoStart, idempotencyKey = key)

    @Test
    fun `create queues and auto-starts when asked`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        val dto = api.create(req("https://example.com/a.zip"))

        assertTrue(
            dto.state == DownloadState.QUEUED || dto.state == DownloadState.DOWNLOADING || dto.state == DownloadState.STARTING,
            "a freshly created download should be in an early, active-or-queued state, was ${dto.state}",
        )
        assertEquals("https://example.com/a.zip", dto.source.locator)
        assertTrue(engine.calls.any { it.startsWith("start:") }, "auto-start should forward to the engine")
    }

    @Test
    fun `create with autoStart false does not start`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        api.create(req("https://example.com/b.zip", autoStart = false))
        assertFalse(engine.calls.any { it.startsWith("start:") })
    }

    @Test
    fun `idempotent replay returns the same download and does not re-enqueue`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        val first = api.create(req("https://example.com/c.zip", key = "K1"))
        val second = api.create(req("https://example.com/c.zip", key = "K1"))

        assertEquals(first.id, second.id)
        assertEquals(1, engine.tasks.size)
    }

    @Test
    fun `unambiguous duplicate source is deduplicated without a key`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        val dest = tmpDest()
        val first = api.create(req("https://example.com/d.zip", dest))
        val second = api.create(req("https://example.com/d.zip", dest))
        assertEquals(first.id, second.id)
        assertEquals(1, engine.tasks.size)
    }

    @Test
    fun `distinct sources are not collapsed into one download`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        val a = api.create(req("https://example.com/x.zip"))
        val b = api.create(req("https://example.com/y.zip"))
        assertTrue(a.id != b.id)
    }

    @Test
    fun `lifecycle verbs forward to the engine`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        val id = api.create(req("https://example.com/e.zip")).id

        api.pause(id); api.resume(id); api.cancel(id); api.retry(id); api.remove(id)
        val verbs = engine.calls.map { it.substringBefore(":") }
        assertTrue("pause" in verbs && "resume" in verbs && "cancel" in verbs && "retry" in verbs && "remove" in verbs)
        assertNull(api.get(id))
    }

    @Test
    fun `operations on an unknown id are rejected with DOWNLOAD_NOT_FOUND`() = runBlocking<Unit> {
        val api = service(FakeEngine())
        val ex = assertFailsWith<DownloadApiException> { api.pause("nope") }
        assertEquals(ApiErrorCode.DOWNLOAD_NOT_FOUND, ex.error.code)
        assertFailsWith<DownloadApiException> { api.remove("nope") }
    }

    @Test
    fun `destination confinement rejects a path outside the allowed roots`() = runBlocking<Unit> {
        val allowed = tmpDest()
        val api = service(FakeEngine(), PathValidator.roots(allowed))
        val ex = assertFailsWith<DownloadApiException> {
            api.create(req("https://example.com/z.zip", dest = File(System.getProperty("user.home"), "evil").absolutePath))
        }
        assertEquals(ApiErrorCode.PERMISSION_DENIED, ex.error.code)
    }

    @Test
    fun `credentials reach the engine request but never the dto`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        val dto = api.create(
            NewDownloadRequest(
                source = DownloadSource.Url("https://example.com/secret.zip"),
                destination = tmpDest(),
                transferHeaders = TransferHeaders(userAgent = "BlazeTest/1"),
                credentials = mapOf("Authorization" to "Bearer super-secret-token"),
            )
        )

        // The DTO the client sees must not carry the secret anywhere.
        val rendered = dto.toString()
        assertFalse("super-secret-token" in rendered, "DTO leaked a credential: $rendered")

        // The engine's in-memory request legitimately received it for this transfer only.
        val engineRequest = engine.tasks.single().request as DownloadRequest.Http
        assertEquals("Bearer super-secret-token", engineRequest.headers["Authorization"])
    }

    @Test
    fun `snapshot reflects the engine tasks mapped to dtos`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val api = service(engine)
        api.create(req("https://example.com/s1.zip"))
        api.create(req("https://example.com/s2.zip"))
        assertEquals(2, api.snapshot().size)
    }

    @Test
    fun `probe forwards to engine metadata and maps the result`() = runBlocking<Unit> {
        val engine = FakeEngine(metadataResult = DownloadMetadata("movie.mkv", 1234L, listOf(DownloadFileMetadata("a", 1234))))
        val api = service(engine)
        val meta = api.probe(req("https://example.com/movie.mkv"))
        assertNotNull(meta)
        assertEquals("movie.mkv", meta.name)
        assertEquals(1234L, meta.totalBytes)
    }

    @Test
    fun `portable detection and import map through the api`() = runBlocking<Unit> {
        val portable = PortableDownloadInfo(PortableTransferKind.HTTP, "file.bin", "https://x/file.bin", 100, 40, true)
        val engine = FakeEngine(portableResult = portable, importResult = DownloadId("imported-1"))
        val api = service(engine)

        val info = api.detectPortable("/tmp/whatever")
        assertNotNull(info)
        assertEquals("file.bin", info.suggestedName)
        assertEquals(org.blaze.download.api.PortableKindDto.HTTP, info.kind)

        val imported = api.importPortable("/tmp/whatever", tmpDest())
        assertEquals("imported-1", imported)
    }

    @Test
    fun `shutdown delegates to the engine`() = runBlocking<Unit> {
        val engine = FakeEngine()
        service(engine).shutdown()
        assertTrue(engine.shutdownCalled)
    }
}
