package org.blaze.engine.hls

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestStaticServer
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun `SAMPLE-AES stream asks for consent before fetching and refuses after acknowledgement`(): Unit = runBlocking {
        val media = plainMedia("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n")
        TestStaticServer(
            mapOf("/media.m3u8" to media, "/seg0.ts" to seg0Plain, "/seg1.ts" to seg1Plain)
        ).use { server ->
            val dest = Files.createTempFile("hls-drm", ".ts")
            val hls = downloader(server)
            try {
                val unacknowledged = hls.download(server.url("/media.m3u8"), dest, -1, emptyMap(), false)
                assertIs<HlsDownloader.Outcome.NeedsDrmConsent>(unacknowledged)
                // Consent must not unlock anything: an acknowledged protected stream is still refused,
                // and no segment was ever requested.
                val acknowledged = hls.download(server.url("/media.m3u8"), dest, -1, emptyMap(), true)
                assertIs<HlsDownloader.Outcome.Unsupported>(acknowledged)
                assertTrue(
                    server.pathRequests.none { it.endsWith(".ts") },
                    "no segment may be fetched for a protected stream"
                )
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
