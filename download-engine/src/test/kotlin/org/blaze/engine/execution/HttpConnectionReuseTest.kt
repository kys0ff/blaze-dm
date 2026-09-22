package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.storage.FileStorage
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the accelerated path reuses TCP connections when the server supports keep-alive.
 *
 * On a segmented transfer the client issues many sequential ranged GETs per worker (one per chunk).
 * If every one of them paid for a fresh TCP (and, on a real server, TLS) handshake, the request
 * overhead would dominate on high-latency links. Ktor's CIO engine is expected to pool and reuse
 * the sockets a keep-alive server leaves open; this test pins that behavior against a server that
 * actually keeps them open, and contrasts it with a server that closes each connection.
 */
class HttpConnectionReuseTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-reuse")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private data class RunResult(val bytes: ByteArray, val connections: Int, val requests: Int)

    private fun download(name: String, keepAlive: Boolean): RunResult {
        val payload = TestHttpServer.of(16 * mib, seed = 909)
        TestHttpServer(payload, keepAlive = keepAlive).use { server ->
            val destination = dir.resolve(name)
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)
            )
            val outcome = runBlocking {
                coordinator.download(
                    request = DownloadRequest.Http(name = name, url = server.url, destination = destination),
                    destination = destination,
                    partial = partial,
                    connections = 2
                ) {}
            }
            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            storage.move(partial, destination)
            val actual = Files.readAllBytes(destination)
            assertEquals(payload.size, actual.size)
            return RunResult(actual, server.connectionCount.get(), server.requestCount.get().toInt())
        }
    }

    @Test
    fun `a keep-alive server needs far fewer sockets than there are chunk requests`() {
        val kept = download("keepalive.bin", keepAlive = true)
        val closed = download("closed.bin", keepAlive = false)

        // Both runs must move the same bytes and issue the same number of GET/HEAD requests.
        assertBytesEqual(kept.bytes, closed.bytes)
        assertTrue(kept.requests >= 16, "expected the 16 chunks to each be a request, got ${kept.requests}")
        assertTrue(closed.requests >= 16, "expected the 16 chunks to each be a request, got ${closed.requests}")

        // Without keep-alive every request is a fresh socket; with it the sockets are pooled.
        println(
            "connection reuse: keep-alive opened ${kept.connections} socket(s) for ${kept.requests} requests; " +
                "Connection:close opened ${closed.connections} socket(s) for ${closed.requests} requests"
        )
        assertTrue(
            closed.connections >= closed.requests / 2,
            "a Connection:close server should open roughly one socket per request, got ${closed.connections}"
        )
        assertTrue(
            kept.connections < closed.connections,
            "keep-alive reused no connections: ${kept.connections} vs ${closed.connections} sockets"
        )
    }

    private fun assertBytesEqual(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertTrue(expected[index] == actual[index], "content differs at byte $index")
        }
    }
}
