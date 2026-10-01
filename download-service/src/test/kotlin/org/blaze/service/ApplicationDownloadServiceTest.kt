package org.blaze.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.service.security.PathValidator
import org.blaze.service.support.FakeEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        conflict: FileConflictBehavior = FileConflictBehavior.ASK,
    ): ApplicationDownloadService {
        val settings = EngineSettingsRepository(Path.of(tmpDest()))
        settings.updateSettingsInMemory { it.copy(fileConflictBehavior = conflict) }
        return ApplicationDownloadService(engine, scope, validator, settings, eventMinIntervalMillis = 0)
    }

    private fun tmpDest(): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "blaze-test-" + System.nanoTime())
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun req(url: String, dest: String = tmpDest(), autoStart: Boolean = true, key: String? = null) =
        NewDownloadRequest(source = DownloadSource.Url(url), destination = dest, autoStart = autoStart, idempotencyKey = key)

    @Test
    fun `create queues and auto-starts when asked`() = runBlocking {
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
    fun `create with autoStart false does not start`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        api.create(req("https://example.com/b.zip", autoStart = false))
        assertFalse(engine.calls.any { it.startsWith("start:") })
    }

    @Test
    fun `idempotent replay returns the same download and does not re-enqueue`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        val first = api.create(req("https://example.com/c.zip", key = "K1"))
        val second = api.create(req("https://example.com/c.zip", key = "K1"))

        assertEquals(first.id, second.id)
        assertEquals(1, engine.tasks.size)
    }

    @Test
    fun `unambiguous duplicate source is deduplicated without a key`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        val dest = tmpDest()
        val first = api.create(req("https://example.com/d.zip", dest))
        val second = api.create(req("https://example.com/d.zip", dest))
        assertEquals(first.id, second.id)
        assertEquals(1, engine.tasks.size)
    }

    @Test
    fun `distinct sources are not collapsed into one download`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        val a = api.create(req("https://example.com/x.zip"))
        val b = api.create(req("https://example.com/y.zip"))
        assertTrue(a.id != b.id)
    }

    @Test
    fun `lifecycle verbs forward to the engine`() = runBlocking {
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
    fun `destination confinement rejects a path outside the allowed roots`() = runBlocking {
        val allowed = tmpDest()
        val api = service(FakeEngine(), PathValidator.roots(allowed))
        val ex = assertFailsWith<DownloadApiException> {
            api.create(req("https://example.com/z.zip", dest = File(System.getProperty("user.home"), "evil").absolutePath))
        }
        assertEquals(ApiErrorCode.PERMISSION_DENIED, ex.error.code)
    }

    @Test
    fun `credentials reach the engine request but never the dto`() = runBlocking {
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
    fun `snapshot reflects the engine tasks mapped to dtos`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        api.create(req("https://example.com/s1.zip"))
        api.create(req("https://example.com/s2.zip"))
        assertEquals(2, api.snapshot().size)
    }

    @Test
    fun `probe forwards to engine metadata and maps the result`() = runBlocking {
        val engine = FakeEngine(metadataResult = DownloadMetadata("movie.mkv", 1234L, listOf(DownloadFileMetadata("a", 1234))))
        val api = service(engine)
        val meta = api.probe(req("https://example.com/movie.mkv"))
        assertNotNull(meta)
        assertEquals("movie.mkv", meta.name)
        assertEquals(1234L, meta.totalBytes)
    }

    @Test
    fun `prepareTorrent forwards url and credentials and maps the preview dto`() = runBlocking {
        val preview = org.blaze.engine.api.TorrentFilePreview(
            torrentFile = Path.of("/cache/abc.torrent"),
            metadata = DownloadMetadata("Big.Buck", 4321L, listOf(DownloadFileMetadata("buck.mkv", 4321))),
        )
        val engine = FakeEngine(torrentFilePreview = preview)
        val api = service(engine)

        val dto = api.prepareTorrent("https://example.com/big.torrent", mapOf("Authorization" to "Bearer t"))

        assertNotNull(dto)
        assertEquals("/cache/abc.torrent", dto.torrentFile)
        assertEquals("Big.Buck", dto.name)
        assertEquals(4321L, dto.totalBytes)
        assertEquals("buck.mkv", dto.files?.single()?.path)
        // The engine saw exactly the URL + credentials handed to the api.
        assertEquals("https://example.com/big.torrent" to mapOf("Authorization" to "Bearer t"), engine.lastTorrentFileRequest)
    }

    @Test
    fun `prepareTorrent returns null when the engine declines`() = runBlocking {
        val api = service(FakeEngine())
        assertNull(api.prepareTorrent("https://example.com/not-a-torrent"))
    }

    @Test
    fun `portable detection and import map through the api`() = runBlocking {
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
    fun `shutdown delegates to the engine`() = runBlocking {
        val engine = FakeEngine()
        service(engine).shutdown()
        assertTrue(engine.shutdownCalled)
    }

    // ── the destination a new download is created with (a torrent owns its folder) ──

    private fun magnetReq(uri: String, dest: String) =
        NewDownloadRequest(source = DownloadSource.Magnet(uri), destination = dest, autoStart = false)

    @Test
    fun `a torrent whose folder is taken is created as a numbered copy`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        val dest = tmpDest()
        Files.createDirectories(Path.of(dest, "Big.Buck")) // the first copy, already on disk

        val dto = api.create(magnetReq("magnet:?xt=urn:btih:aaa&dn=Big.Buck", dest))
        val enqueued = engine.tasks.single().request as DownloadRequest.Torrent

        assertEquals("Big.Buck (1)", dto.name, "the row must say which copy it is")
        assertEquals(Path.of(dest, "Big.Buck (1)"), enqueued.destination)
        assertEquals("Big.Buck (1)", enqueued.name, "name and folder must travel together")
    }

    @Test
    fun `the numbered copy steps past every folder already taken`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)
        val dest = tmpDest()
        listOf("Big.Buck", "Big.Buck (1)", "Big.Buck (2)").forEach { Files.createDirectories(Path.of(dest, it)) }

        val dto = api.create(magnetReq("magnet:?xt=urn:btih:aaa&dn=Big.Buck", dest))

        assertEquals("Big.Buck (3)", dto.name)
    }

    @Test
    fun `a free folder is created under the name the source gave it`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine)

        val dto = api.create(magnetReq("magnet:?xt=urn:btih:aaa&dn=Big.Buck", tmpDest()))

        assertEquals("Big.Buck", dto.name)
    }

    @Test
    fun `a policy that does not rename leaves the folder the caller pointed at`() = runBlocking {
        val engine = FakeEngine()
        val api = service(engine, conflict = FileConflictBehavior.OVERWRITE)
        val dest = tmpDest()
        Files.createDirectories(Path.of(dest, "Big.Buck"))

        val dto = api.create(magnetReq("magnet:?xt=urn:btih:aaa&dn=Big.Buck", dest))

        assertEquals("Big.Buck", dto.name, "overwriting is the engine's business, not a silent rename")
    }

    @Test
    fun `a seeding download does not swallow a fresh copy of its own source`() = runBlocking {
        val dest = tmpDest()
        val uri = "magnet:?xt=urn:btih:seed&dn=Seed"
        val seeding = DownloadTask(
            id = DownloadId("seeding-1"),
            name = "Seed",
            request = DownloadRequest.Torrent("Seed", TorrentSource.Magnet(uri), Path.of(dest, "Seed")),
            state = org.blaze.engine.api.DownloadState.Seeding,
            totalBytes = 100,
            downloadedBytes = 100,
            downloadSpeed = 0,
        )
        // No renaming, so the only thing standing between the two adds is the duplicate check.
        val api = service(FakeEngine(initial = listOf(seeding)), conflict = FileConflictBehavior.OVERWRITE)

        val dto = api.create(magnetReq(uri, dest))

        assertNotEquals("seeding-1", dto.id, "a seeding task has all its bytes; it is not an in-flight duplicate")
    }

    @Test
    fun `a torrent still transferring is still deduplicated`() = runBlocking {
        val dest = tmpDest()
        val uri = "magnet:?xt=urn:btih:live&dn=Live"
        val running = DownloadTask(
            id = DownloadId("running-1"),
            name = "Live",
            request = DownloadRequest.Torrent("Live", TorrentSource.Magnet(uri), Path.of(dest, "Live")),
            state = org.blaze.engine.api.DownloadState.Downloading,
            totalBytes = 100,
            downloadedBytes = 40,
            downloadSpeed = 1024,
        )
        val api = service(FakeEngine(initial = listOf(running)))

        val dto = api.create(magnetReq(uri, dest))

        assertEquals("running-1", dto.id)
    }
}
