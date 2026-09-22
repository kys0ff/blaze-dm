package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Recovery from unhealthy connections without permanently losing parallelism.
 *
 * These tests pin the behavior the previous pass explicitly left unresolved: a stalled worker used
 * to be *dropped* (its coroutine cancelled) and never replaced, so every stall shrank the pool for
 * the rest of the transfer - and a single stalled connection in the tail was only noticed once all
 * progress had stopped. The current design aborts only the stalled request and re-queues its chunk
 * while the worker stays alive, so N connections stay N until the work is genuinely done.
 *
 * The production JDK client is used on purpose: it has no per-socket read timeout, so the watchdog
 * really is the only thing standing between a stalled connection and an endless hang.
 */
class HttpStalledWorkerTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-stall")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private fun run(url: String, name: String, settings: DownloadSettings, connections: Int, metrics: EngineMetrics): HttpDownloadCoordinator.Outcome {
        val destination = dir.resolve(name)
        val partial = storage.getPartialFile(destination).toPath()
        val coordinator = HttpDownloadCoordinator(
            client = HttpNetworkClient(client),
            storage = storage,
            limiter = BandwidthLimiter(),
            settings = settings,
            metrics = metrics
        )
        val outcome = runBlocking {
            // A generous ceiling: if a stalled chunk were never recovered the transfer would hang
            // past this and fail the test instead of silently passing.
            withTimeout(90_000) {
                coordinator.download(
                    request = DownloadRequest.Http(name = name, url = url, destination = destination),
                    destination = destination,
                    partial = partial,
                    connections = connections
                ) {}
            }
        }
        if (outcome is HttpDownloadCoordinator.Outcome.Success) storage.move(partial, destination)
        return outcome
    }

    @Test
    fun `a stalled connection is recovered without losing the transfer`(): Unit = runBlocking {
        // 16 one-megabyte chunks over 4 connections; the first three chunk bodies hang forever.
        // Every one of them has to be detected, dropped, and re-read by a healthy connection.
        val payload = TestHttpServer.of(16 * mib, seed = 1234)
        TestHttpServer(payload).use { server ->
            server.hangFirstBodies = 3
            server.hangMillis = 20_000
            val metrics = EngineMetrics()
            val outcome = run(
                url = server.url,
                name = "stalled.bin",
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1, httpStalledConnectionSeconds = 1),
                connections = 4,
                metrics = metrics
            )

            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome, "a stalled connection must not fail the download")
            assertBytes(payload, dir.resolve("stalled.bin"))
            assertTrue(
                metrics.snapshot().stalledWorkerDrops >= 1,
                "the watchdog never fired: ${metrics.snapshot()}"
            )
        }
    }

    @Test
    fun `stalled slow and failed connections together still finish byte-exact`(): Unit = runBlocking {
        // One transfer that mixes all three pathologies at once:
        //  - the first 2 bodies hang (stalled)      -> watchdog must abort & re-queue
        //  - the next 3 bodies are cut short (fail) -> chunk retry must re-read them
        //  - everything else is delivered normally  -> healthy workers keep draining the pool
        val payload = TestHttpServer.of(20 * mib, seed = 99)
        TestHttpServer(payload, cutFirstBodies = 3).use { server ->
            server.hangFirstBodies = 2
            server.hangMillis = 20_000
            val metrics = EngineMetrics()
            val outcome = run(
                url = server.url,
                name = "mixed.bin",
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1, httpStalledConnectionSeconds = 1),
                connections = 5,
                metrics = metrics
            )

            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome, "mixed failures must recover, not fail")
            assertBytes(payload, dir.resolve("mixed.bin"))
            assertEquals(20L * mib, outcome.downloadedBytes)
            val snap = metrics.snapshot()
            assertTrue(snap.stalledWorkerDrops >= 1, "no stall recovered: $snap")
            assertTrue(snap.chunkRetries >= 1, "no truncated chunk retried: $snap")
        }
    }

    @Test
    fun `a host that stalls every connection fails after bounded retries instead of looping forever`() {
        // Pathological server: it hangs more bodies than there are total chunks, so no connection
        // ever completes. The attempt budget must turn that into a bounded failure, not an infinite
        // abort/re-queue storm. This is the retry-storm guard for the new recovery path.
        val payload = TestHttpServer.of(8 * mib, seed = 5)
        TestHttpServer(payload).use { server ->
            server.hangFirstBodies = Int.MAX_VALUE
            server.hangMillis = 3_000
            val started = System.nanoTime()
            val outcome = run(
                url = server.url,
                name = "allstall.bin",
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1, httpStalledConnectionSeconds = 1),
                connections = 2,
                metrics = EngineMetrics()
            )
            val elapsedSec = (System.nanoTime() - started) / 1_000_000_000

            assertIs<HttpDownloadCoordinator.Outcome.Failure>(outcome, "an always-stalling host must eventually fail")
            // Bounded: it must not run away toward the withTimeout ceiling.
            assertTrue(elapsedSec < 90, "recovery looped for ${elapsedSec}s - suspected retry storm")
        }
    }

    private fun assertBytes(expected: ByteArray, path: Path) {
        val actual = Files.readAllBytes(path)
        assertEquals(expected.size, actual.size, "byte count of ${path.fileName}")
        var mismatch = -1
        for (index in expected.indices) {
            if (expected[index] != actual[index]) {
                mismatch = index
                break
            }
        }
        assertTrue(mismatch < 0, "content of ${path.fileName} differs starting at byte $mismatch")
    }
}
