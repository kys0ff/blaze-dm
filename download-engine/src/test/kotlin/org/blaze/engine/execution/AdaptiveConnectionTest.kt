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
 * End-to-end proof that the adaptive connection window actually engages against a throttling host,
 * and - crucially - that it does so *without* breaking correctness: the file still lands byte-exact.
 *
 * The observable signal is [EngineMetrics.minConnectionWindow], which the coordinator records from the
 * window's `onChange` hook: a non-zero value below the worker count is direct evidence that a 429 /
 * 503 shrank concurrency mid-transfer. The control run (adaptive disabled) must leave it at 0, so the
 * reduction is attributable to the feature and not to some unrelated pool effect.
 *
 * `keepAlive = true` keeps the socket pool steady so the only thing moving is the logical window.
 */
class AdaptiveConnectionTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-adaptive-conn")
    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private fun run(name: String, url: String, connections: Int, adaptive: Boolean, metrics: EngineMetrics) {
        val destination = dir.resolve(name)
        val partial = storage.getPartialFile(destination).toPath()
        val coordinator = HttpDownloadCoordinator(
            client = HttpNetworkClient(client),
            storage = storage,
            limiter = BandwidthLimiter(),
            metrics = metrics,
            settings = DownloadSettings(
                httpChunkSizeMb = 1,
                httpMinParallelSizeBytes = 1,
                httpAdaptiveConnectionsEnabled = adaptive,
            ),
        )
        val outcome = runBlocking {
            withTimeout(60_000) {
                coordinator.download(
                    request = DownloadRequest.Http(name, url, destination),
                    destination = destination,
                    partial = partial,
                    connections = connections,
                ) {}
            }
        }
        assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome, "adaptive shedding must not fail the download")
        storage.move(partial, destination)
    }

    @Test
    fun `a throttling host shrinks the connection window and the file is still byte-exact`() {
        val payload = TestHttpServer.of(8 * mib, seed = 4242)
        TestHttpServer(payload, keepAlive = true).use { server ->
            // First four chunk requests (one per worker) come back 429. That is the whole trigger:
            // the workers must observe a throttle and shed the window before the retries succeed.
            server.failStatus = 429
            server.failFirstN = 4
            val metrics = EngineMetrics()

            run("adaptive-on.bin", server.url, connections = 4, adaptive = true, metrics = metrics)

            assertBytes(payload, dir.resolve("adaptive-on.bin"))
            val shed = metrics.snapshot().minConnectionWindow
            assertTrue(
                shed in 1..3,
                "the window should have been shed below the 4-worker pool under 429s, was $shed: ${metrics.snapshot()}",
            )
        }
    }

    @Test
    fun `with the feature off a throttling host leaves the window untouched`() {
        val payload = TestHttpServer.of(8 * mib, seed = 4242)
        TestHttpServer(payload, keepAlive = true).use { server ->
            server.failStatus = 429
            server.failFirstN = 4
            val metrics = EngineMetrics()

            run("adaptive-off.bin", server.url, connections = 4, adaptive = false, metrics = metrics)

            // Same workload, but with adaptive disabled the gate never runs, so nothing is recorded.
            assertEquals(0, metrics.snapshot().minConnectionWindow, "disabled window must never record a shed")
            assertBytes(payload, dir.resolve("adaptive-off.bin"))
        }
    }

    @Test
    fun `a healthy host never engages the window`() {
        val payload = TestHttpServer.of(8 * mib, seed = 7)
        TestHttpServer(payload, keepAlive = true).use { server ->
            val metrics = EngineMetrics()
            run("healthy.bin", server.url, connections = 4, adaptive = true, metrics = metrics)
            // No throttle means no reduction; the gate is a no-op on the fast path.
            assertEquals(0, metrics.snapshot().minConnectionWindow, "a healthy host must not shed connections")
            assertBytes(payload, dir.resolve("healthy.bin"))
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
