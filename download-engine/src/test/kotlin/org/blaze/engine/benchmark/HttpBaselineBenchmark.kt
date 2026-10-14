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
import org.blaze.engine.support.PayloadGenerator
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Baseline HTTP measurement required by PROMPT.md §Phase-2 ("record a baseline benchmark").
 *
 * Same recipe every later phase compares against:
 *   - 32 MiB LCG payload from [PayloadGenerator], so a mid-size transfer is big enough that a per-
 *     socket handshake does not dominate but small enough that the whole suite fits in a minute.
 *   - connections ∈ {1, 4, 8, 16}: the interesting scaling curve for a segmented download.
 *   - two scenarios:
 *       * unthrottled loopback - measures engine overhead (dispatcher churn, buffer allocation,
 *         coordinator bookkeeping) since the network is essentially free.
 *       * per-connection 4 MiB/s - the CDN-style cap where multi-connection work-stealing earns its
 *         keep; ideal scaling is `throughput ≈ connections × 4 MiB/s` until something else binds.
 *
 * Runs one warm-up and three samples per configuration. The runner reports median wall time,
 * min/max spread, GC pauses and collections, peak heap and peak thread count.
 *
 * Enabled with `-Dblaze.benchmark=true` (`./gradlew :download-engine:httpBenchmark`). Numbers are
 * printed to stdout; the operator pastes them into `docs/PROGRESS.md` at the end of each phase.
 */
class HttpBaselineBenchmark {

    private val enabled get() = System.getProperty("blaze.benchmark") == "true"
    private val dir: Path = Files.createTempDirectory("blaze-http-baseline")
    private val storage = DefaultFileStorage()
    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `baseline HTTP throughput and resource cost at 1 4 8 16 connections`() {
        if (!enabled) return
        val mib = PayloadGenerator.MIB
        val sizeMib = 32
        val payload = TestHttpServer.of(sizeMib * mib, seed = 0xB0_55)

        val lines = StringBuilder()
        lines.appendLine("=== HTTP baseline (payload=$sizeMib MiB, warmups=1, samples=3) ===")

        for (connections in listOf(1, 4, 8, 16)) {
            val unthrottled = runOne(
                label = "unthrottled conn=$connections",
                payload = payload,
                connections = connections,
                perConnectionBytesPerSec = 0L,
            )
            lines.appendLine(unthrottled.summary.render())

            val throttled = runOne(
                label = "throttled-4MiB/s conn=$connections",
                payload = payload,
                connections = connections,
                perConnectionBytesPerSec = 4L * mib,
            )
            lines.appendLine(throttled.summary.render())
        }

        println(lines)
    }

    private class RunResult(val summary: BenchmarkRunner.Summary, val bytesServed: Long, val connectionCount: Int)

    private fun runOne(label: String, payload: ByteArray, connections: Int, perConnectionBytesPerSec: Long): RunResult {
        var lastServed = 0L
        var lastConns = 0
        val summary = BenchmarkRunner.run(label = label, warmups = 1, samples = 3) { index ->
            val timer = BenchmarkRunner.timer(label, payload.size.toLong())
            TestHttpServer(payload, perConnectionBytesPerSec = perConnectionBytesPerSec, keepAlive = true).use { server ->
                val destination = dir.resolve("baseline-$index-$connections.bin")
                val partial = storage.getPartialFile(destination).toPath()
                val coordinator = HttpDownloadCoordinator(
                    client = HttpNetworkClient(client),
                    storage = storage,
                    limiter = BandwidthLimiter(),
                    settings = DownloadSettings(
                        httpChunkSizeMb = 1,
                        httpMinParallelSizeBytes = 1,
                    ),
                )
                val outcome = runBlocking {
                    coordinator.download(
                        request = DownloadRequest.Http("baseline-$index", server.url, destination),
                        destination = destination,
                        partial = partial,
                        connections = connections,
                    ) { /* progress callback unused for benchmark */ }
                }
                Files.deleteIfExists(partial)
                Files.deleteIfExists(destination)
                lastServed = server.bytesServed.get()
                lastConns = server.connectionCount.get()
                timer.markFirstByte()
                check(outcome is HttpDownloadCoordinator.Outcome.Success) { "baseline run failed: $outcome" }
            }
            timer.stop(extra = mapOf("bytesServed" to lastServed, "serverConnections" to lastConns))
        }
        return RunResult(summary, lastServed, lastConns)
    }
}
