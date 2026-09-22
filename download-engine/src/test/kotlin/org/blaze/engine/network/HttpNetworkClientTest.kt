package org.blaze.engine.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.support.TestHttpServer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two decisions the transfer layer makes from server headers - "can this be split?" and
 * "did the whole body arrive?" - plus the header parsing they come back from.
 */
class HttpNetworkClientTest {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
        followRedirects = false
    }

    private lateinit var subject: HttpNetworkClient

    @BeforeTest
    fun setUp() {
        subject = HttpNetworkClient(client)
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private fun request(url: String) = DownloadRequest.Http(
        name = "file.bin",
        url = url,
        destination = Files.createTempFile("blaze-client-test", ".bin")
    )

    // ------------------------------------------------------------------ header parsing

    @Test
    fun `content-range yields body length and whole-file length`() {
        assertEquals(801L to 2453845L, HttpNetworkClient.parseContentRange("bytes 200-1000/2453845"))
        assertEquals(1L to 42L, HttpNetworkClient.parseContentRange("bytes 0-0/42"))
        assertEquals(4096L to 4096L, HttpNetworkClient.parseContentRange("BYTES 0-4095/4096"))
    }

    @Test
    fun `unusable content-range forms fall back to content-length`() {
        assertNull(HttpNetworkClient.parseContentRange(null))
        assertNull(HttpNetworkClient.parseContentRange("bytes */80"))
        assertNull(HttpNetworkClient.parseContentRange("bytes 0-9/*"))
        assertNull(HttpNetworkClient.parseContentRange("bytes 1000-200/2453845"))
        assertNull(HttpNetworkClient.parseContentRange("nonsense"))
    }

    @Test
    fun `open-ended ranges are built without a fake end`() {
        assertEquals("bytes=100-", HttpNetworkClient.buildRange(100, null))
        assertEquals("bytes=0-0", HttpNetworkClient.buildRange(0, 0))
    }

    @Test
    fun `statuses map to the errors the retry layer understands`() {
        assertIs<DownloadError.NotFound>(HttpNetworkClient.errorForStatus(404))
        assertIs<DownloadError.NotFound>(HttpNetworkClient.errorForStatus(410))
        assertIs<DownloadError.Unauthorized>(HttpNetworkClient.errorForStatus(401))
        assertIs<DownloadError.Unauthorized>(HttpNetworkClient.errorForStatus(403))
        assertIs<DownloadError.RangeUnsupported>(HttpNetworkClient.errorForStatus(416))
        assertIs<DownloadError.NetworkFailure>(HttpNetworkClient.errorForStatus(429))
        assertIs<DownloadError.NetworkFailure>(HttpNetworkClient.errorForStatus(500))
    }

    // ------------------------------------------------------------------ probing

    @Test
    fun `a server that proves ranged reads is worth splitting`(): Unit = runBlocking {
        val payload = TestHttpServer.of(128 * 1024)
        TestHttpServer(payload, validator = "\"v1\"").use { server ->
            val probe = subject.probe(request(server.url))

            assertTrue(probe.acceptsRanges, "ranged support should have been proven")
            assertEquals(payload.size.toLong(), probe.totalBytes)
            assertEquals("\"v1\"", probe.validator)
            assertTrue(probe.knownSize)
        }
    }

    @Test
    fun `a server without range support is reported as unsplitable`(): Unit = runBlocking {
        TestHttpServer(TestHttpServer.of(64 * 1024), supportsRanges = false).use { server ->
            val probe = subject.probe(request(server.url))

            assertFalse(probe.acceptsRanges, "Accept-Ranges must never be trusted on its own")
            assertEquals(64L * 1024, probe.totalBytes)
        }
    }

    @Test
    fun `a server that rejects HEAD is still probed with a ranged GET`(): Unit = runBlocking {
        TestHttpServer(TestHttpServer.of(64 * 1024), headAllowed = false).use { server ->
            val probe = subject.probe(request(server.url))

            assertTrue(probe.acceptsRanges)
            assertEquals(64L * 1024, probe.totalBytes)
        }
    }

    @Test
    fun `a dead server produces an empty probe instead of throwing`(): Unit = runBlocking {
        val probe = subject.probe(request("http://127.0.0.1:1/nope"))

        assertEquals(0, probe.status)
        assertFalse(probe.acceptsRanges)
        assertFalse(probe.knownSize)
    }

    // ------------------------------------------------------------------ streaming

    @Test
    fun `a successful stream hands over exactly the requested slice`(): Unit = runBlocking {
        val payload = TestHttpServer.of(100 * 1024)
        TestHttpServer(payload).use { server ->
            val collected = ByteArrayOutputStream()
            val result = subject.stream(request(server.url), start = 1024, end = 2047) { bytes, length ->
                collected.write(bytes, 0, length)
            }

            assertIs<HttpNetworkClient.StreamResult.Success>(result)
            assertEquals(1024L, result.received)
            assertContentEquals(payload.copyOfRange(1024, 2048), collected.toByteArray())
            assertEquals(listOf("bytes=1024-2047"), server.rangeRequests.filter { it.isNotEmpty() })
        }
    }

    @Test
    fun `a server ignoring the range is reported instead of corrupting the file`(): Unit = runBlocking {
        TestHttpServer(TestHttpServer.of(64 * 1024), supportsRanges = false).use { server ->
            val result = subject.stream(request(server.url), start = 10_000) { _, _ -> }

            assertIs<HttpNetworkClient.StreamResult.RangeIgnored>(result)
        }
    }

    @Test
    fun `a body that ends early is truncated, not complete`(): Unit = runBlocking {
        TestHttpServer(TestHttpServer.of(256 * 1024), earlyCloseAfterBytes = 40 * 1024).use { server ->
            val result = subject.stream(request(server.url)) { _, _ -> }

            assertIs<HttpNetworkClient.StreamResult.Truncated>(result)
            assertEquals(256L * 1024, result.expected)
            assertTrue(result.received in 1 until result.expected)
        }
    }

    @Test
    fun `a resumed request sends a range header`(): Unit = runBlocking {
        TestHttpServer(TestHttpServer.of(64 * 1024)).use { server ->
            val collected = ByteArrayOutputStream()
            val result = subject.stream(request(server.url), start = 4096) { bytes, length ->
                collected.write(bytes, 0, length)
            }

            assertIs<HttpNetworkClient.StreamResult.Success>(result)
            assertEquals(60L * 1024, result.received)
            assertContentEquals(TestHttpServer.of(64 * 1024).copyOfRange(4096, 64 * 1024), collected.toByteArray())
            assertTrue(server.rangeRequests.any { it == "bytes=4096-" }, "no range was requested: ${server.rangeRequests}")
        }
    }
}
