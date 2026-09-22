package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.metrics.EngineMetrics
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
 * Locks down the connection-model assumption the whole accelerated path rests on.
 *
 * The production client is the JDK `HttpClient` (via Ktor's Java engine). For that engine, "run N
 * connections" really means "keep up to N range requests in flight at once": the JDK pool hands each
 * concurrent GET its own HTTP/1.1 keep-alive socket instead of serialising them. That is exactly the
 * behaviour a segmented downloader needs - a server that caps throughput *per TCP connection* is only
 * beaten by genuinely parallel sockets.
 *
 * This matters because of HTTP/2: if these requests were multiplexed onto a single HTTP/2 connection,
 * a per-connection server cap would no longer be divisible and the "N connections" mental model would
 * quietly become "N streams on one socket". The engine stays on HTTP/1.1 (the JDK default, and the
 * loopback test server only speaks HTTP/1.1), and this test verifies the concurrency it buys.
 */
class HttpParallelismTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-parallelism")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private data class Result(val maxConcurrent: Int, val sockets: Int, val requests: Int, val peakWorkers: Int)

    private fun download(name: String, connections: Int, perConnectionKib: Long): Result {
        val payload = TestHttpServer.of(16 * mib, seed = 4242)
        // A per-connection cap makes every in-flight chunk take measurable wall-clock time, so the
        // server can observe how many requests genuinely overlap on the wire.
        TestHttpServer(payload, perConnectionBytesPerSec = perConnectionKib * 1024, keepAlive = true).use { server ->
            val destination = dir.resolve(name)
            val partial = storage.getPartialFile(destination).toPath()
            val metrics = EngineMetrics()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                metrics = metrics,
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)
            )
            val outcome = runBlocking {
                coordinator.download(
                    request = DownloadRequest.Http(name = name, url = server.url, destination = destination),
                    destination = destination,
                    partial = partial,
                    connections = connections
                ) {}
            }
            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            storage.move(partial, destination)
            assertEquals(payload.size, Files.readAllBytes(destination).size)
            return Result(
                maxConcurrent = server.maxConcurrentRequests.get(),
                sockets = server.connectionCount.get(),
                requests = server.requestCount.get().toInt(),
                peakWorkers = metrics.snapshot().peakWorkers
            )
        }
    }

    @Test
    fun `a segmented download keeps multiple range requests in flight at once`() {
        // 16 chunks over 4 connections at 4 MiB/s each: chunks must overlap or the transfer would be
        // one serial GET after another. Peak in-flight concurrency has to exceed 1 to prove the pool
        // runs them in parallel, and the worker pool should actually reach the requested width.
        val result = download("parallel.bin", connections = 4, perConnectionKib = 4L * 1024)
        println(
            "parallelism: 4 connections -> peak ${result.maxConcurrent} concurrent requests, " +
                "${result.requests} requests over ${result.sockets} sockets, peak workers ${result.peakWorkers}"
        )

        assertTrue(result.maxConcurrent >= 2, "requests were serialised, never more than ${result.maxConcurrent} in flight")
        assertEquals(4, result.peakWorkers, "the worker pool never reached the requested width")
        // Chunk requests dominate the request count; with keep-alive the sockets stay far below it.
        assertTrue(result.requests >= 16, "expected >= 16 chunk GETs, got ${result.requests}")
        assertTrue(result.sockets < result.requests, "connections were not reused: ${result.sockets} sockets for ${result.requests} requests")
    }

    @Test
    fun `more connections raise observed request concurrency`() {
        // The scaling of peak concurrency with the configured width is what turns a per-connection
        // server cap into aggregate throughput. If the client silently funnelled everything through
        // one socket, both widths would show the same peak and segmentation would be pointless.
        val two = download("width2.bin", connections = 2, perConnectionKib = 4L * 1024)
        val four = download("width4.bin", connections = 4, perConnectionKib = 4L * 1024)
        println("width scaling: 2 conn -> peak ${two.maxConcurrent}, 4 conn -> peak ${four.maxConcurrent}")

        assertTrue(four.maxConcurrent > two.maxConcurrent, "4 connections did not exceed 2: ${four.maxConcurrent} vs ${two.maxConcurrent}")
    }
}
