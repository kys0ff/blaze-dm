package org.blaze.engine.hls

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadError
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestStaticServer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tier-A HLS resilience, measured over the wire against the loopback harness.
 *
 * The segment fetcher used to flatten *every* HTTP outcome to a byte array (or null), which caused two
 * real bugs: a truncated body was accepted as a successful segment and concatenated into a corrupt file,
 * and a transient 5xx/429 became a fake "not found" that was treated as permanent and killed the whole
 * run on a single blip. These tests pin the corrected behaviour: a short segment is retried and never
 * written short, a transient refusal is retried on the shared backoff schedule, and a genuine 404 fails
 * fast without burning the retry budget.
 */
class HlsSegmentResilienceTest {

    private val media = (
            "#EXTM3U\n" +
                    "#EXT-X-TARGETDURATION:10\n" +
                    "#EXTINF:10,\n" +
                    "seg0.ts\n" +
                    "#EXTINF:10,\n" +
                    "seg1.ts\n" +
                    "#EXT-X-ENDLIST\n"
            ).toByteArray()

    // MPEG-TS segments begin with the 0x47 sync byte so the container sniff classifies them as TS.
    private val seg0 = byteArrayOf(0x47) + ByteArray(181) { it.toByte() }
    private val seg1 = byteArrayOf(0x47) + ByteArray(213) { (it + 5).toByte() }

    private val http = HttpClient(CIO)
    private val storage = DefaultFileStorage()

    @AfterTest
    fun tearDown() {
        http.close()
    }

    @Test
    fun `a truncated segment is retried and never assembled short`(): Unit = runBlocking {
        // The very first GET of seg0 stops short of its advertised Content-Length; the retry gets the
        // whole body. The old fetcher would have written the 5-byte stub and produced a silently short file.
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/seg0.ts" to seg0, "/seg1.ts" to seg1)
        ).use { server ->
            server.truncateFirst("/seg0.ts", times = 1, bytes = 5)
            val dest = Files.createTempFile("hls-trunc", ".ts")
            try {
                val outcome = withTimeout(30_000) {
                    downloader().download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                }
                assertIs<HlsDownloader.Outcome.Success>(outcome, "the retry must recover, got $outcome")
                assertEquals(seg0.size + seg1.size, Files.readAllBytes(dest).size, "the file must be the full length")
                assertEquals(seg0.toList() + seg1.toList(), Files.readAllBytes(dest).toList())
                assertTrue(
                    server.hitCount("/seg0.ts") >= 2,
                    "a truncated segment must be re-requested, not accepted short",
                )
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `a transient 503 on a segment is retried instead of failing the run`(): Unit = runBlocking {
        // Before the fix a 503 was reported as NotFound and treated as permanent, dying on one blip.
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/seg0.ts" to seg0, "/seg1.ts" to seg1)
        ).use { server ->
            server.failFirst("/seg1.ts", times = 1, status = 503)
            val dest = Files.createTempFile("hls-transient", ".ts")
            try {
                val outcome = withTimeout(30_000) {
                    downloader().download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                }
                assertIs<HlsDownloader.Outcome.Success>(outcome, "a single 503 must be retried, got $outcome")
                assertEquals(seg0.toList() + seg1.toList(), Files.readAllBytes(dest).toList())
                assertTrue(server.hitCount("/seg1.ts") >= 2, "the throttled segment must have been re-requested")
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `a permanent 404 on a segment fails fast without retrying`(): Unit = runBlocking {
        // 404 is fatal: re-asking cannot help, so the run gives up on the first answer instead of
        // spending the retry budget (with its backoff delays) on a doomed request.
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/seg0.ts" to seg0, "/seg1.ts" to seg1)
        ).use { server ->
            server.failFirst("/seg0.ts", times = 99, status = 404)
            val dest = Files.createTempFile("hls-fatal", ".ts")
            try {
                val outcome = withTimeout(30_000) {
                    downloader().download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                }
                val failed = assertIs<HlsDownloader.Outcome.Failed>(outcome, "a missing segment must fail the run")
                assertEquals(DownloadError.NotFound, failed.error)
                assertEquals(1, server.hitCount("/seg0.ts"), "a fatal 404 must not be retried")
                assertTrue(!Files.exists(dest) || Files.size(dest) == 0L, "no corrupt file may be produced")
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    private fun downloader(): HlsDownloader =
        HlsDownloader(HttpNetworkClient(http), storage, BandwidthLimiter())
}
