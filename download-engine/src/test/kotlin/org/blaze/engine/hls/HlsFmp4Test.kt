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
 * Tier-B fragmented-MP4 (CMAF) HLS assembly, over the wire against the loopback harness.
 *
 * A single-track fMP4 stream is described by an `#EXT-X-MAP` init segment (the `ftyp`+`moov` header) plus
 * media fragments (`moof`+`mdat`), which are often packed as byte windows into one `.m4s` via
 * `#EXT-X-BYTERANGE`. Concatenating init-then-fragments in order is already a valid ISOBMFF file - no
 * remuxing - so the whole correctness burden is: fetch the init once and put it first, and slice every
 * fragment at exactly its advertised byte window. These tests pin that byte-exactness, the concurrent-safe
 * resolution of the offset-omitted `n` byterange form, and that a missing init is a hard failure rather
 * than a silently dropped header (which would yield an unplayable file).
 */
class HlsFmp4Test {

    // A recognisable init header and two fragments. The exact bytes only matter for the byte-exact assert;
    // nothing here is real MP4 structure because the concat path is a pure byte stream.
    private val init = "ftyp".toByteArray() + ByteArray(28) { (it + 1).toByte() }
    private val frag0 = ByteArray(40) { (100 + it).toByte() }
    private val frag1 = ByteArray(60) { (200 + it).toByte() }

    private val http = HttpClient(CIO)
    private val storage = DefaultFileStorage()

    @AfterTest
    fun tearDown() {
        http.close()
    }

    @Test
    fun `init segment is prepended to whole-fragment m4s segments byte-exactly`(): Unit = runBlocking {
        val media = (
                "#EXTM3U\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                        "#EXTINF:10,\n" +
                        "frag0.m4s\n" +
                        "#EXTINF:10,\n" +
                        "frag1.m4s\n" +
                        "#EXT-X-ENDLIST\n"
                ).toByteArray()
        TestStaticServer(
            mapOf(
                "/media.m3u8" to media,
                "/init.mp4" to init,
                "/frag0.m4s" to frag0,
                "/frag1.m4s" to frag1,
            )
        ).use { server ->
            val dest = Files.createTempFile("hls-fmp4-whole", ".mp4")
            try {
                val outcome = withTimeout(30_000) {
                    downloader().download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                }
                assertIs<HlsDownloader.Outcome.Success>(outcome, "fMP4 concat must succeed, got $outcome")
                // init (ftyp+moov) then fragments (moof+mdat) in order - no remux, byte-for-byte.
                assertEquals((init + frag0 + frag1).toList(), Files.readAllBytes(dest).toList())
                assertEquals(1, server.hitCount("/init.mp4"), "the init segment is fetched exactly once")
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `byte-range fragments are sliced from one m4s and concatenated after the init`(): Unit = runBlocking {
        // One 130-byte .m4s is cut into three windows: an explicit 50@0, then the offset-omitted forms
        // ("50" continues at byte 50, "30" continues at byte 100). The parser must resolve those omitted
        // offsets into absolute windows at parse time, because fragments are fetched concurrently and the
        // continuation cannot be reconstructed from fetch order. Slices cover the resource exactly once.
        val whole = ByteArray(130) { (it * 3 + 7).toByte() }
        val media = (
                "#EXTM3U\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                        "#EXT-X-BYTERANGE:50@0\n" +
                        "#EXTINF:10,\n" +
                        "frag.m4s\n" +
                        "#EXT-X-BYTERANGE:50\n" +
                        "#EXTINF:10,\n" +
                        "frag.m4s\n" +
                        "#EXT-X-BYTERANGE:30\n" +
                        "#EXTINF:10,\n" +
                        "frag.m4s\n" +
                        "#EXT-X-ENDLIST\n"
                ).toByteArray()
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/init.mp4" to init, "/frag.m4s" to whole)
        ).use { server ->
            val dest = Files.createTempFile("hls-fmp4-br", ".mp4")
            try {
                val outcome = withTimeout(30_000) {
                    downloader().download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                }
                assertIs<HlsDownloader.Outcome.Success>(outcome, "byte-range assembly must succeed, got $outcome")
                // Every window is pulled via Range (three 206 slices) and re-joined in order after the init.
                assertEquals((init + whole).toList(), Files.readAllBytes(dest).toList())
                assertEquals(3, server.rangeRequestCount.get().toInt(), "each fragment slice is a ranged GET")
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `a missing init segment fails hard and writes no file`(): Unit = runBlocking {
        // The init header defines the container; silently skipping it would emit an unplayable file. So a
        // permanent 404 on EXT-X-MAP is a hard failure, even though every fragment downloaded cleanly.
        val media = (
                "#EXTM3U\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                        "#EXTINF:10,\n" +
                        "frag0.m4s\n" +
                        "#EXT-X-ENDLIST\n"
                ).toByteArray()
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/init.mp4" to init, "/frag0.m4s" to frag0)
        ).use { server ->
            server.failFirst("/init.mp4", times = 99, status = 404)
            val dest = Files.createTempFile("hls-fmp4-noinit", ".mp4")
            try {
                val outcome = withTimeout(30_000) {
                    downloader().download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                }
                val failed = assertIs<HlsDownloader.Outcome.Failed>(outcome, "a missing init must fail the run")
                assertEquals(DownloadError.NotFound, failed.error)
                assertTrue(!Files.exists(dest) || Files.size(dest) == 0L, "no header-less file may be produced")
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `parser resolves offset-omitted byteranges to absolute windows`() {
        // Offline proof of the parse-time resolution the concurrent fetch relies on: the second window has
        // no '@offset', so it must continue at byte 50 (the end of the first), not restart at 0.
        val media = (
                "#EXTM3U\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                        "#EXT-X-BYTERANGE:50@0\n" +
                        "#EXTINF:10,\n" +
                        "frag.m4s\n" +
                        "#EXT-X-BYTERANGE:50\n" +
                        "#EXTINF:10,\n" +
                        "frag.m4s\n" +
                        "#EXT-X-ENDLIST\n"
                ).toByteArray().decodeToString()
        val playlist = HlsPlaylistParser.parse(media, "http://host/media.m3u8")
        assertEquals("http://host/init.mp4", playlist.initMapUri)
        assertEquals(listOf(0L to 50L, 50L to 50L), playlist.segments.map { it.byteRange!!.start to it.byteRange!!.length })
    }

    private fun downloader(): HlsDownloader =
        HlsDownloader(HttpNetworkClient(http), storage, BandwidthLimiter())
}
