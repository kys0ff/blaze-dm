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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the `If-Range` correctness gap from Phase 1 §15.9: a segmented transfer must never finalise a
 * file whose chunks straddle a mid-transfer rotation of the server's representation.
 *
 * The wire-level tests exercise the primitive directly (the client must actually send the header, and
 * a spec-compliant server must answer a stale validator with a whole-body 200). The end-to-end test
 * proves the coordinator turns that 200 into a *clean restart* rather than a corrupted file: it
 * discards the mixed bytes, re-probes against the new validator, and finishes byte-exact inside a
 * single [HttpDownloadCoordinator.download] call, reporting the event as a genuine restart.
 *
 * `keepAlive = true` keeps the socket pool steady so the only thing moving mid-run is the validator.
 */
class IfRangeTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-ifrange")
    private val httpClient: HttpClient = DownloadManager.createDefaultHttpClient()
    private val networkClient = HttpNetworkClient(httpClient)

    @AfterTest
    fun tearDown() {
        httpClient.close()
        dir.toFile().deleteRecursively()
    }

    // ---------------------------------------------------------------- wire level

    @Test
    fun `stream sends the If-Range header and a matching validator still returns a 206`() {
        val payload = TestHttpServer.of(4 * mib, seed = 11)
        TestHttpServer(payload, keepAlive = true, validator = "W/base").use { server ->
            val received = AtomicLong(0)
            val result = runBlocking {
                networkClient.stream(
                    request = DownloadRequest.Http("match", server.url, dir.resolve("match.bin")),
                    start = 0,
                    end = payload.size.toLong() - 1,
                    ifRange = "W/base",
                ) { _, length -> received.addAndGet(length.toLong()) }
            }
            assertIs<HttpNetworkClient.StreamResult.Success>(result, "an If-Range that matches must keep serving the range")
            assertEquals(payload.size.toLong(), received.get(), "the full range should stream through")
            assertTrue(server.ifRangeRequests.contains("W/base"), "the If-Range header must actually reach the wire")
            assertEquals(0, server.ifRangeMismatchServed.get(), "a matching validator is never downgraded to a whole body")
        }
    }

    @Test
    fun `a stale If-Range validator turns the ranged request into a whole-body 200`() {
        val payload = TestHttpServer.of(4 * mib, seed = 12)
        TestHttpServer(payload, keepAlive = true, validator = "W/base").use { server ->
            val result = runBlocking {
                networkClient.stream(
                    request = DownloadRequest.Http("stale", server.url, dir.resolve("stale.bin")),
                    start = 0,
                    end = payload.size.toLong() - 1,
                    ifRange = "W/stale",
                ) { _, _ -> }
            }
            assertIs<HttpNetworkClient.StreamResult.RangeIgnored>(
                result,
                "a mismatched If-Range makes the server answer the whole body, which is not the range we asked for",
            )
            assertTrue(server.ifRangeMismatchServed.get() >= 1, "the server must have taken the mismatch path")
        }
    }

    // ---------------------------------------------------------------- end to end

    @Test
    fun `a mid-transfer ETag rotation is detected via If-Range and re-fetched byte-exact`() {
        val payload = TestHttpServer.of(8 * mib, seed = 99)
        TestHttpServer(payload, keepAlive = true, validator = "W/base").use { server ->
            // The probe (HEAD + `bytes=0-0`) is the first two requests; from the third on the host
            // rotates the ETag, so every real chunk request carries a now-stale If-Range.
            server.etagSwapAfterRequests = 2
            server.etagAfterSwap = "W/rotated"

            var restarts = 0
            val destination = dir.resolve("rotated.bin")
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = networkClient,
                storage = storage,
                limiter = BandwidthLimiter(),
                metrics = EngineMetrics(),
                settings = DownloadSettings(
                    httpChunkSizeMb = 1,
                    httpMinParallelSizeBytes = 1,
                ),
            )

            val outcome = runBlocking {
                withTimeout(60_000) {
                    coordinator.download(
                        request = DownloadRequest.Http("rotated", server.url, destination),
                        destination = destination,
                        partial = partial,
                        connections = 4,
                        onRestart = { restarts++ },
                    ) {}
                }
            }
            assertIs<HttpDownloadCoordinator.Outcome.Success>(
                outcome,
                "a rotation must be recovered inside one download(), not surfaced as a failure or a corrupt file",
            )
            storage.move(partial, destination)

            assertBytes(payload, destination)
            assertTrue(
                server.ifRangeMismatchServed.get() >= 1,
                "the stale If-Range must have been served a whole body at least once on the first attempt",
            )
            assertTrue(restarts >= 1, "discarding the mixed bytes is a genuine restart and must be reported")
            assertTrue(
                server.ifRangeRequests.contains("W/rotated"),
                "the re-fetch must send the new validator, so it lines up with the rotated file",
            )
        }
    }

    @Test
    fun `a healthy host never triggers the If-Range restart`() {
        val payload = TestHttpServer.of(8 * mib, seed = 5)
        TestHttpServer(payload, keepAlive = true, validator = "W/steady").use { server ->
            var restarts = 0
            val destination = dir.resolve("steady.bin")
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = networkClient,
                storage = storage,
                limiter = BandwidthLimiter(),
                metrics = EngineMetrics(),
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1),
            )
            val outcome = runBlocking {
                withTimeout(60_000) {
                    coordinator.download(
                        request = DownloadRequest.Http("steady", server.url, destination),
                        destination = destination,
                        partial = partial,
                        connections = 4,
                        onRestart = { restarts++ },
                    ) {}
                }
            }
            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            storage.move(partial, destination)
            assertBytes(payload, destination)
            // The validator matched every request, so the range was never downgraded and never a restart.
            assertEquals(0, server.ifRangeMismatchServed.get(), "a steady validator must always be served the 206")
            assertEquals(0, restarts, "a healthy host must not restart the transfer")
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
