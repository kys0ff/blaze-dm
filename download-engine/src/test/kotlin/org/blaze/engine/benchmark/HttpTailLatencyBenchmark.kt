package org.blaze.engine.benchmark

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.execution.HttpDownloadCoordinator
import org.blaze.engine.metrics.EngineMetrics
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
 * Does the engine suffer from tail latency - i.e. does one slow connection at the end serialise the
 * finish and force a "last 1-5%" stall?
 *
 * The prior report listed "slow-worker / partial-chunk takeover when a few chunks remain" as an
 * open optimization. Before adding any runtime chunk-splitting we have to establish whether the tail
 * is actually a bottleneck, because the planner already clamps chunk size so a file decomposes into
 * ~connections*8 units (see [org.blaze.engine.execution.HttpTransferPlannerTest]). If those units
 * already let several workers drain a slow final region together, late splitting buys nothing and
 * would only add coordination risk.
 *
 * Two measurements:
 *  1. a *uniform* per-connection cap - how close does a real segmented transfer get to the ideal
 *     aggregate-throughput time? The gap is the residual tail cost that splitting would attack.
 *  2. a *slow tail* - only the final region of the file is throttled hard. If the tail is
 *     parallelised, doubling the connection count roughly halves the time through the slow region;
 *     if it were serialised on one connection, the count would not matter.
 *
 * Numbers are machine-dependent and printed; only clear directional invariants are asserted. Run
 * with `./gradlew :download-engine:httpBenchmark`.
 */
class HttpTailLatencyBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"
    private val storage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-tail")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private data class Sample(val seconds: Double, val peakWorkers: Int, val tailMillis: Long)

    private fun run(
        payload: ByteArray,
        perConnectionKib: Long,
        connections: Int,
        slowTailFromByte: Long = -1L,
        slowTailKib: Long = 0L
    ): Sample {
        TestHttpServer(payload, perConnectionBytesPerSec = perConnectionKib * 1024, keepAlive = true).use { server ->
            server.slowTailFromByte = slowTailFromByte
            server.slowTailBytesPerSec = slowTailKib * 1024

            val name = "tail-$connections-${slowTailFromByte}.bin"
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
            val snap = metrics.snapshot()
            Files.deleteIfExists(partial)
            Files.deleteIfExists(destination)
            return Sample(elapsed, snap.peakWorkers, snap.lastTailMillis)
        }
    }

    @Test
    fun `a uniform per-connection cap leaves little residual tail`() {
        if (!enabled) return

        // 32 MiB against a server that caps each connection at 8 MiB/s. The ideal is size / (conns * rate).
        val sizeMib = 32
        val payload = TestHttpServer.of(sizeMib * mib, seed = 777)
        val perConnection = 8L * 1024
        val connections = 4
        val ideal = sizeMib / (connections * (perConnection / 1024.0))

        run(payload, perConnection, connections) // warm the pool
        val sample = run(payload, perConnection, connections)
        val overhead = sample.seconds / ideal

        println(
            (
                "Tail overhead, uniform cap (%d MiB @ %d MiB/s/conn, %d conn): ideal %.2f s, actual %.2f s " +
                    "= %.2fx, peak workers %d"
                ).format(
                sizeMib, perConnection / 1024, connections, ideal, sample.seconds, overhead, sample.peakWorkers
            )
        )

        // If the finish were serialised on a single connection the run would approach `ideal * conns`.
        // Staying well under that (and keeping every worker busy) shows chunk granularity already
        // bounds the tail, so late chunk-splitting has little left to reclaim.
        assertTrue(overhead < connections / 2.0, "tail looks serialised: ${overhead}x of ideal")
        assertTrue(sample.peakWorkers == connections, "expected all $connections workers active, saw ${sample.peakWorkers}")
    }

    @Test
    fun `a slow final region is drained in parallel, not serialised on one connection`() {
        if (!enabled) return

        // Only the last 8 MiB is throttled hard (1 MiB/s/connection); the first 24 MiB runs at the
        // normal 8 MiB/s cap. The question is whether the slow tail is spread over the connection
        // pool or funnelled through a single worker that everyone else waits on.
        val sizeMib = 32
        val slowRegionMib = 8
        val payload = TestHttpServer.of(sizeMib * mib, seed = 778)
        val perConnection = 8L * 1024
        val slowTailKib = 1L * 1024
        val slowFrom = (sizeMib - slowRegionMib).toLong() * mib

        run(payload, perConnection, 4, slowFrom, slowTailKib) // warm
        val two = run(payload, perConnection, 2, slowFrom, slowTailKib)
        val four = run(payload, perConnection, 4, slowFrom, slowTailKib)

        // Serialised on one connection both would take ~slowRegion/slowRate = 8 s regardless of count.
        val serialised = slowRegionMib / (slowTailKib / 1024.0)
        println(
            (
                "Slow tail (%d MiB @ %d MiB/s/conn tail): 2 conn = %.2f s (peak %d), 4 conn = %.2f s (peak %d); " +
                    "one-connection serialised reference = %.2f s"
                ).format(
                slowRegionMib, slowTailKib / 1024, two.seconds, two.peakWorkers, four.seconds, four.peakWorkers, serialised
            )
        )

        // More connections must cut the tail (a serialised tail would not respond at all), and neither
        // may approach the single-connection worst case.
        assertTrue(four.seconds < two.seconds, "4 connections did not beat 2 on the tail: ${four.seconds} vs ${two.seconds}")
        assertTrue(two.seconds < serialised, "the tail was serialised on one connection: ${two.seconds} vs ${serialised}s")
    }
}
