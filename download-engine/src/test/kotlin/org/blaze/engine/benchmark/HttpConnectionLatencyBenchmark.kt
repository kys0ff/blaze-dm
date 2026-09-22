package org.blaze.engine.benchmark

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.execution.HttpDownloadCoordinator
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The connection-establishment half of the WAN/TLS story - measured, not assumed.
 *
 * Localhost benchmarks hide the single most important reason to reuse connections: on a real host
 * every fresh socket costs round trips (TCP handshake, and for HTTPS a TLS handshake with its own
 * RTTs and key exchange). A segmented downloader issues many requests, so the cost of *opening*
 * them can swamp the cost of *reading* them. That is also why the engine moved off CIO (one socket
 * per request) onto the JDK client, which pools and reuses keep-alive sockets.
 *
 * A real WAN/TLS measurement is impossible to do reproducibly or honestly inside this repo (no
 * controlled external host), so this models it the only sound way: an injected per-socket setup cost
 * ([TestHttpServer.connectDelayMillis], charged once per TCP connection) that stands in for handshake
 * latency. What it can legitimately show is the *structural* effect the previous pass claimed -
 * reuse collapses many per-request handshakes into a handful, and the saving grows with request
 * count and with per-handshake cost. Numbers are machine-dependent and printed; the directional
 * invariants are asserted. Run with `./gradlew :download-engine:httpBenchmark`.
 */
class HttpConnectionLatencyBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"
    private val storage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-latency")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private data class Sample(val seconds: Double, val sockets: Int, val requests: Int)

    private fun measure(connections: Int, keepAlive: Boolean, connectDelayMs: Long): Sample {
        val payload = TestHttpServer.of(16 * mib, seed = 31337)
        TestHttpServer(payload, keepAlive = keepAlive).use { server ->
            server.connectDelayMillis = connectDelayMs
            val name = "lat-$connections-${keepAlive}-$connectDelayMs.bin"
            val destination = dir.resolve(name)
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                // 1 MiB chunks => 16 range requests, so per-request handshake cost is amplified.
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)
            )
            val started = System.nanoTime()
            runBlocking {
                coordinator.download(
                    request = DownloadRequest.Http(name, server.url, destination),
                    destination = destination,
                    partial = partial,
                    connections = connections
                ) {}
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            Files.deleteIfExists(partial)
            Files.deleteIfExists(destination)
            return Sample(elapsed, server.connectionCount.get(), server.requestCount.get().toInt())
        }
    }

    @Test
    fun `reusing connections beats a fresh handshake per chunk when setup is expensive`() {
        if (!enabled) return

        // High per-connection setup cost stands in for a WAN + TLS handshake.
        val handshake = 150L
        val closed = measure(4, keepAlive = false, handshake)
        val kept = measure(4, keepAlive = true, handshake)
        println(
            (
                "connection reuse @ %d ms/socket setup: keep-alive %.2f s over %d sockets, " +
                    "Connection:close %.2f s over %d sockets (%.2f s saved, %d requests)"
                ).format(
                handshake, kept.seconds, kept.sockets, closed.seconds, closed.sockets,
                closed.seconds - kept.seconds, kept.requests
            )
        )

        // Reuse must open far fewer sockets and finish faster; without reuse each request re-pays.
        assertTrue(kept.sockets < closed.sockets, "reuse opened no fewer sockets: ${kept.sockets} vs ${closed.sockets}")
        assertTrue(kept.seconds < closed.seconds, "reuse did not save time: ${kept.seconds}s vs ${closed.seconds}s")
    }

    @Test
    fun `report the latency vs concurrency matrix`() {
        if (!enabled) return

        // Informational grid for the report: connection cost across the vertical axis (free loopback
        // vs expensive setup) and concurrency across the horizontal one (2 vs 8 connections). It shows
        // the two effects a segmented downloader balances - more sockets amortise a slow stream, but
        // each extra socket is another handshake you pay for unless the pool reuses it.
        println("Connection-latency matrix (16 MiB, 1 MiB chunks):")
        println(String.format("  %-22s %10s %10s", "scenario", "sockets", "seconds"))
        for (handshake in listOf(0L, 150L)) {
            for (connections in listOf(2, 8)) {
                val kept = measure(connections, keepAlive = true, handshake)
                val closed = measure(connections, keepAlive = false, handshake)
                println("  %-22s %10d %10.2f  (close: %d sockets, %.2f s)".format(
                    "setup=${handshake}ms conn=$connections", kept.sockets, kept.seconds, closed.sockets, closed.seconds
                ))
            }
        }
        assertTrue(true)
    }
}
