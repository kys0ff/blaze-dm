package org.blaze.service.mapping

import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadApiException
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.DownloadState as PublicState
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.TransferHeaders
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState as EngineState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the engine↔API translation in place.
 *
 * These mappings are the seam that keeps engine internals from leaking upward, so they are tested
 * directly: every engine state must have a public counterpart, request validation must reject bad
 * sources with stable codes, and the DTO projection must never surface a credential.
 */
class MappingTest {

    private val dir = Path.of("/tmp/blaze-map")

    @Test
    fun `every engine state has a public counterpart`() {
        val pairs = mapOf(
            EngineState.Queued to PublicState.QUEUED,
            EngineState.Starting to PublicState.STARTING,
            EngineState.Downloading to PublicState.DOWNLOADING,
            EngineState.Pausing to PublicState.PAUSING,
            EngineState.Paused to PublicState.PAUSED,
            EngineState.Resuming to PublicState.RESUMING,
            EngineState.Verifying to PublicState.VERIFYING,
            EngineState.ResolvingMetadata to PublicState.RESOLVING_METADATA,
            EngineState.Completed to PublicState.COMPLETED,
            EngineState.Seeding to PublicState.SEEDING,
            EngineState.Failed to PublicState.FAILED,
            EngineState.Cancelled to PublicState.CANCELLED,
        )
        pairs.forEach { (engine, public) -> assertEquals(public, engine.toPublic()) }
    }

    @Test
    fun `http request is validated and headers are forwarded to the engine request`() {
        val req = NewDownloadRequest(
            source = DownloadSource.Url("https://example.com/f.zip"),
            destination = dir.toString(),
            filename = "renamed.zip",
            connections = 4,
            transferHeaders = TransferHeaders(userAgent = "UA/1"),
            credentials = mapOf("Authorization" to "Bearer t"),
        )
        val engine = RequestMapper.toEngineRequest(req, dir) as DownloadRequest.Http
        assertEquals("renamed.zip", engine.name)
        assertEquals(4, engine.segmentCount)
        assertEquals("UA/1", engine.headers["User-Agent"])
        assertEquals("Bearer t", engine.headers["Authorization"])
    }

    @Test
    fun `non-http scheme is rejected with INVALID_URL`() {
        val ex = assertFailsWith<DownloadApiException> {
            RequestMapper.toEngineRequest(
                NewDownloadRequest(source = DownloadSource.Url("file:///etc/passwd"), destination = dir.toString()), dir
            )
        }
        assertEquals(ApiErrorCode.INVALID_URL, ex.error.code)
    }

    @Test
    fun `a non-magnet string is rejected as an unsupported source`() {
        val ex = assertFailsWith<DownloadApiException> {
            RequestMapper.toEngineRequest(
                NewDownloadRequest(source = DownloadSource.Magnet("http://not-a-magnet"), destination = dir.toString()), dir
            )
        }
        assertEquals(ApiErrorCode.UNSUPPORTED_SOURCE, ex.error.code)
    }

    @Test
    fun `a blank torrent path is an invalid request`() {
        assertFailsWith<DownloadApiException> {
            RequestMapper.toEngineRequest(
                NewDownloadRequest(source = DownloadSource.TorrentFile("  "), destination = dir.toString()), dir
            )
        }
    }

    @Test
    fun `dto projection never carries credential material`() {
        val task = DownloadTask(
            id = DownloadId("id-1"),
            name = "report.pdf",
            request = DownloadRequest.Http(
                name = "report.pdf",
                url = "https://example.com/report.pdf",
                destination = dir.resolve("report.pdf"),
                headers = mapOf("Cookie" to "session=hunter2"),
            ),
            state = EngineState.Downloading,
            totalBytes = 1000,
            downloadedBytes = 400,
            downloadSpeed = 50,
        )
        val dto = task.toDto()
        assertEquals(PublicState.DOWNLOADING, dto.state)
        assertEquals("https://example.com/report.pdf", dto.source.locator)
        // The DTO may *hint* that credentials are needed, but never carries their value or header name.
        assertTrue(dto.source.mayRequireCredentials)
        assertFalse("hunter2" in dto.toString(), "credential value leaked into the DTO")
        assertFalse("Cookie" in dto.toString(), "cookie header name leaked into the DTO")
    }

    @Test
    fun `progress is derived and a paused download is resumable`() {
        val task = DownloadTask(
            id = DownloadId("id-2"),
            name = "t",
            request = DownloadRequest.Torrent("t", TorrentSource.Magnet("magnet:?xt=urn:btih:abc"), dir.resolve("t")),
            state = EngineState.Paused,
            totalBytes = 200,
            downloadedBytes = 100,
            downloadSpeed = 0,
            createdAt = Instant.now().minusSeconds(10),
        )
        val dto = task.toDto()
        assertEquals(PublicState.PAUSED, dto.state)
        assertTrue(dto.resumable)
        assertEquals(0.5f, dto.progress ?: 0f, 0.001f)
    }
}
