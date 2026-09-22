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

/**
 * Where does client-side concurrency stop helping?
 *
 * The original benchmark only compared 1 vs 4 connections against a server that caps each
 * connection at 4 MiB/s, so "4 connections ~= 4x" is true *by construction*: the bottleneck is the
 * server, not the engine. That tells us nothing about the engine's own ceiling.
 *
 * This benchmark drives an *unthrottled* loopback server (so the network is effectively free) and
 * sweeps the connection count, which is what surfaces the real limits: TCP connection setup (the
 * test server answers `Connection: close`, so every chunk is a fresh socket), the CIO per-host
 * connection pool, coroutine/dispatcher contention and the disk write path. The numbers are
 * machine-dependent and printed, not asserted.
 */
class HttpConcurrencyScalingBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"
    private val storage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-scaling")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private fun measure(connections: Int, sizeMib: Int): Pair<Double, Double> {
        val payload = TestHttpServer.of(sizeMib * mib, seed = 2024)
        // keep-alive server: with the JDK engine the workers reuse sockets across chunks, so the
        // sweep measures steady-state throughput rather than per-chunk TCP connect cost.
        TestHttpServer(payload, keepAlive = true).use { server ->
            val destination = dir.resolve("scale-$connections.bin")
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                // 1 MiB chunks => enough units for every worker to pull its weight at 16 ways.
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)
            )
            val started = System.nanoTime()
            runBlocking {
                coordinator.download(
                    request = DownloadRequest.Http("scale", server.url, destination),
                    destination = destination,
                    partial = partial,
                    connections = connections
                ) {}
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            println(
                "    (%d conn: %d sockets for %d requests)".format(
                    connections, server.connectionCount.get(), server.requestCount.get()
                )
            )
            Files.deleteIfExists(partial)
            Files.deleteIfExists(destination)
            return sizeMib / elapsed to elapsed
        }
    }

    @Test
    fun `sweep connection counts against an unthrottled server`() {
        if (!enabled) return
        val sizeMib = 32
        println("HTTP concurrency scaling vs an UNthrottled loopback server (${sizeMib} MiB, 1 MiB chunks):")
        var previous = 0.0
        for (connections in listOf(1, 2, 4, 8, 16)) {
            // warm-up, then a measured run
            measure(connections, sizeMib)
            val (mibps, seconds) = measure(connections, sizeMib)
            val speedup = if (previous > 0) " (%.2fx over prev)".format(mibps / previous) else ""
            println("  %2d connections: %7.2f MiB/s in %.3f s%s".format(connections, mibps, seconds, speedup))
            previous = mibps
        }
    }
}
