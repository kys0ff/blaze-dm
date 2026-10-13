package org.blaze.engine.hls

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.runBlocking
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestStaticServer
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/**
 * End-to-end tests for the HLS path against a real loopback HTTP server that serves a master playlist,
 * a media playlist, segments and a key as *separate* resources. These prove the pieces assemble into a
 * correct file over the wire — resolution order, AES-128 decryption with sequence-derived IVs, honest
 * DRM refusal and live rejection — rather than trusting the offline parser alone.
 */
class HlsDownloaderIntegrationTest {

    private val master = (
            "#EXTM3U\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360\n" +
                    "media.m3u8\n"
            ).toByteArray()

    private fun plainMedia(keyLine: String = "") = (
            "#EXTM3U\n" +
                    "#EXT-X-TARGETDURATION:10\n" +
                    keyLine +
                    "#EXTINF:10,\n" +
                    "seg0.ts\n" +
                    "#EXTINF:10,\n" +
                    "seg1.ts\n" +
                    "#EXT-X-ENDLIST\n"
            ).toByteArray()

    // MPEG-TS segments must begin with the 0x47 sync byte so the container sniff classifies them as TS.
    private val seg0Plain = byteArrayOf(0x47) + ByteArray(37) { it.toByte() }
    private val seg1Plain = byteArrayOf(0x47) + ByteArray(53) { (it + 7).toByte() }

    @Test
    fun `VOD MPEG-TS stream is assembled from master through to a single file`(): Unit = runBlocking {
        TestStaticServer(
            mapOf(
                "/master.m3u8" to master,
                "/media.m3u8" to plainMedia(),
                "/seg0.ts" to seg0Plain,
                "/seg1.ts" to seg1Plain,
            )
        ).use { server ->
            val dest = Files.createTempFile("hls-vod", ".ts")
            val outcome = downloader(server).download(server.url("/master.m3u8"), dest, -1, emptyMap(), false)
            try {
                assertIs<HlsDownloader.Outcome.Success>(outcome, "expected success, got $outcome")
                assertContentEquals(seg0Plain + seg1Plain, Files.readAllBytes(dest))
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `AES-128 segments are decrypted with sequence-derived IVs and reassembled`(): Unit = runBlocking {
        val key = ByteArray(16) { (it * 3 + 1).toByte() }
        val seg0Cipher = aes128(Cipher.ENCRYPT_MODE, key, defaultIvFor(0), seg0Plain)
        val seg1Cipher = aes128(Cipher.ENCRYPT_MODE, key, defaultIvFor(1), seg1Plain)
        val media = plainMedia("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n")
        TestStaticServer(
            mapOf(
                "/media.m3u8" to media,
                "/key" to key,
                "/seg0.ts" to seg0Cipher,
                "/seg1.ts" to seg1Cipher,
            )
        ).use { server ->
            val dest = Files.createTempFile("hls-aes", ".ts")
            val outcome = downloader(server).download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
            try {
                assertIs<HlsDownloader.Outcome.Success>(outcome, "expected success, got $outcome")
                assertContentEquals(seg0Plain + seg1Plain, Files.readAllBytes(dest))
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `SAMPLE-AES stream asks for consent before any segment is fetched`(): Unit = runBlocking {
        val media = plainMedia("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n")
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/seg0.ts" to seg0Plain, "/seg1.ts" to seg1Plain)
        ).use { server ->
            val dest = Files.createTempFile("hls-drm", ".ts")
            try {
                val unacknowledged = downloader(server).download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                assertIs<HlsDownloader.Outcome.NeedsDrmConsent>(unacknowledged)
                // The warning gates fetching: nothing is requested until the user chooses to continue.
                assertTrue(
                    server.pathRequests.none { it.endsWith(".ts") },
                    "no segment may be fetched before the user acknowledges the warning"
                )
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `acknowledging proceeds to fetch accessible segments but never bypasses SAMPLE-AES`(): Unit = runBlocking {
        val media = plainMedia("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n")
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/seg0.ts" to seg0Plain, "/seg1.ts" to seg1Plain)
        ).use { server ->
            val dest = Files.createTempFile("hls-drm", ".ts")
            try {
                val acknowledged = downloader(server).download(server.url("/media.m3u8"), dest, -1, emptyMap(), true)
                // The classification alone no longer blocks: the ordinary path ran and pulled the
                // accessible segments...
                assertTrue(
                    server.pathRequests.any { it.endsWith(".ts") },
                    "an acknowledged stream must be attempted on the ordinary download path"
                )
                // ...but Blaze refused to fake a playable file from essence it cannot decrypt, naming the
                // actual scheme rather than the warning, and it never requested the protected key.
                assertIs<HlsDownloader.Outcome.Unsupported>(
                    acknowledged,
                    "expected an honest limitation, got $acknowledged"
                )
                assertTrue(
                    acknowledged.reason.contains("SAMPLE-AES", ignoreCase = true),
                    "the reason must name the real scheme, not just the warning: ${acknowledged.reason}"
                )
                assertTrue(
                    server.pathRequests.none { it.endsWith("/key") },
                    "Blaze must not fetch a protected DRM key"
                )
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `a stream flagged only by a session-key advertisement still downloads when its segments are clear`(): Unit =
        runBlocking {
            // The detector labels this DrmSuspected (a #EXT-X-SESSION-KEY names FairPlay), yet the variant's own
            // segments carry no EXT-X-KEY and are served in the clear - exactly the case where a "DRM" label
            // must NOT, by itself, block the download. After acknowledging, the ordinary path assembles a
            // playable MPEG-TS file, proving detection is treated as a warning rather than proof of inaccessibility.
            val media = (
                    "#EXTM3U\n" +
                            "#EXT-X-TARGETDURATION:10\n" +
                            "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"https://drm.example/key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n" +
                            "#EXTINF:10,\n" +
                            "seg0.ts\n" +
                            "#EXTINF:10,\n" +
                            "seg1.ts\n" +
                            "#EXT-X-ENDLIST\n"
                    ).toByteArray()
            TestStaticServer(
                mapOf("/media.m3u8" to media, "/seg0.ts" to seg0Plain, "/seg1.ts" to seg1Plain)
            ).use { server ->
                val dest = Files.createTempFile("hls-sessionkey", ".ts")
                try {
                    val hls = downloader(server)
                    val inspection = hls.inspect(server.url("/media.m3u8"), -1, emptyMap())
                    assertEquals(HlsProtection.DrmSuspected, inspection.playlist.protection)
                    val outcome = hls.download(server.url("/media.m3u8"), dest, -1, emptyMap(), true)
                    assertIs<HlsDownloader.Outcome.Success>(outcome, "expected success, got $outcome")
                    assertContentEquals(seg0Plain + seg1Plain, Files.readAllBytes(dest))
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `live event playlist is reported unsupported rather than assembled`(): Unit = runBlocking {
        val live = (
                "#EXTM3U\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXTINF:10,\n" +
                        "seg0.ts\n" +
                        "#EXTINF:10,\n" +
                        "seg1.ts\n"
                ).toByteArray()
        TestStaticServer(
            mapOf("/media.m3u8" to live, "/seg0.ts" to seg0Plain, "/seg1.ts" to seg1Plain)
        ).use { server ->
            val dest = Files.createTempFile("hls-live", ".ts")
            try {
                val outcome = downloader(server).download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                assertIs<HlsDownloader.Outcome.Unsupported>(outcome)
                assertTrue(outcome.reason.contains("live", ignoreCase = true))
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `progress reports cumulative downloaded bytes rather than a segment counter`(): Unit = runBlocking {
        // Regression guard for the "1b, 2b" symptom: the callback must expose real byte totals so the
        // executor can render a proper progress bar / speed / size instead of a bare 1..N segment index.
        TestStaticServer(
            mapOf(
                "/master.m3u8" to master,
                "/media.m3u8" to plainMedia(),
                "/seg0.ts" to seg0Plain,
                "/seg1.ts" to seg1Plain,
            )
        ).use { server ->
            val dest = Files.createTempFile("hls-progress", ".ts")
            val reports = ConcurrentLinkedQueue<Triple<Int, Int, Long>>()
            val outcome = downloader(server).download(
                server.url("/master.m3u8"), dest, -1, emptyMap(), false,
            ) { done, total, bytes -> reports.add(Triple(done, total, bytes)) }
            try {
                assertIs<HlsDownloader.Outcome.Success>(outcome, "expected success, got $outcome")
                val expectedBytes = (seg0Plain.size + seg1Plain.size).toLong()
                val snap = reports.toList()
                assertEquals(2, snap.maxOf { it.second }, "two segments are advertised in total")
                val finalReport = snap.last { it.first == 2 }
                assertEquals(
                    expectedBytes, finalReport.third,
                    "the completing tick must carry the real byte total, never the segment index (2)",
                )
                assertTrue(
                    snap.map { it.third }.zipWithNext().all { (a, b) -> b >= a },
                    "byte progress must never go backwards",
                )
                assertTrue(expectedBytes > 2L, "byte totals are far larger than a raw segment count")
            } finally {
                Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `inspect lists variants and classifies protection without fetching media`(): Unit = runBlocking {
        TestStaticServer(
            mapOf(
                "/master.m3u8" to master,
                "/media.m3u8" to plainMedia("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n"),
            )
        ).use { server ->
            val inspection = downloader(server).inspect(server.url("/master.m3u8"), -1, emptyMap())
            assertEquals(1, inspection.variants.size)
            assertEquals(1_000_000L, inspection.variants.first().bandwidth)
            assertEquals(HlsProtection.DrmSuspected, inspection.playlist.protection)
            assertTrue(server.pathRequests.none { it.endsWith(".ts") }, "inspect must not pull segments")
        }
    }

    private fun downloader(server: TestStaticServer): HlsDownloader {
        val http = HttpClient(CIO)
        return HlsDownloader(HttpNetworkClient(http), DefaultFileStorage(), BandwidthLimiter())
    }

    private fun aes128(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Mirrors the engine's implicit IV: the segment index as a big-endian 128-bit value. */
    private fun defaultIvFor(index: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (index ushr (8 * i)).toByte()
        return iv
    }
}
