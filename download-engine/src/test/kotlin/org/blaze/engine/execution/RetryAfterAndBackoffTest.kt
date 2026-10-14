package org.blaze.engine.execution

import io.ktor.client.HttpClient
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3 correctness coverage for the two things a throttling host needs the engine to obey:
 *
 *  - `Retry-After` on a 429 / 503, both the wire-level parse and the way it lands on the coordinator
 *    and the task-level scheduler.
 *  - Exponential backoff with full jitter on chunk retries when the server does *not* send a hint
 *    (so a flaky host still gets spaced-out attempts instead of a hammer).
 *
 * The pure parser is exercised directly so the delta-seconds and HTTP-date branches stay pinned
 * even if the fault-injection server's knob set changes. The end-to-end tests then check the
 * observable wall-time consequence of a hint being honoured: with a single retry that asks for a
 * 2 s pause, the download cannot finish in under 2 s, and with a "no hint at all" 500 it still
 * cannot retry instantly because the exponential schedule has a base.
 */
class RetryAfterAndBackoffTest {

    private val mib = 1024 * 1024
    private val dir: Path = Files.createTempDirectory("blaze-retry-backoff")
    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    // ---------------------------------------------------------- pure parse contract

    @Test
    fun `parseRetryAfter accepts delta-seconds and clamps to the ceiling`() {
        val headers = headersOf(HttpHeaders.RetryAfter to "120")
        assertEquals(
            120_000L,
            HttpNetworkClient.parseRetryAfter(headers, HttpStatusCode.TooManyRequests.value),
            "delta-seconds form must be converted to millis",
        )
        val tooBig = headersOf(HttpHeaders.RetryAfter to "999999")
        assertEquals(
            HttpNetworkClient.RETRY_AFTER_CEILING_MILLIS,
            HttpNetworkClient.parseRetryAfter(tooBig, HttpStatusCode.ServiceUnavailable.value),
            "an oversized hint must clamp to the ceiling instead of parking a chunk forever",
        )
    }

    @Test
    fun `parseRetryAfter accepts HTTP-date and returns the remaining window`() {
        val future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val hint = HttpNetworkClient.parseRetryAfter(headersOf(HttpHeaders.RetryAfter to future), HttpStatusCode.TooManyRequests.value)
        assertTrue(hint != null && hint in 55_000L..65_000L, "date-form hint should be ~60 s, got $hint")
    }

    @Test
    fun `parseRetryAfter ignores anything on a non-throttling status and on a malformed value`() {
        // A 200 with a stray Retry-After is a bug on the server side, not a hint we should honour.
        assertNull(HttpNetworkClient.parseRetryAfter(headersOf(HttpHeaders.RetryAfter to "10"), 200))
        // A 500 is retryable in general but not a throttling signal.
        assertNull(HttpNetworkClient.parseRetryAfter(headersOf(HttpHeaders.RetryAfter to "10"), 500))
        // Missing header, unparseable value, or a negative count all fall through to "no hint".
        assertNull(HttpNetworkClient.parseRetryAfter(headersOf(), HttpStatusCode.TooManyRequests.value))
        assertNull(HttpNetworkClient.parseRetryAfter(headersOf(HttpHeaders.RetryAfter to "not-a-number"), HttpStatusCode.TooManyRequests.value))
        assertNull(HttpNetworkClient.parseRetryAfter(headersOf(HttpHeaders.RetryAfter to "-5"), HttpStatusCode.TooManyRequests.value))
    }

    // ---------------------------------------------------------- chunk-level behaviour

    @Test
    fun `a chunk retry waits for the Retry-After the server sent`() {
        // 8 MiB split into 1 MiB chunks across 4 workers: any chunk-level 429 with a hint of
        // 2 seconds must delay the next attempt of *that chunk* by at least that long. The rest
        // of the download is fast (loopback) so "elapsed < 2s" is a real failure signal, not noise.
        val payload = TestHttpServer.of(8 * mib, seed = 9999)
        TestHttpServer(payload, keepAlive = true).use { server ->
            server.failStatus = HttpStatusCode.TooManyRequests.value
            server.failFirstN = 1
            server.retryAfter = "2"
            val destination = dir.resolve("retry-honoured.bin")
            val started = System.currentTimeMillis()
            val outcome = runBlocking {
                withTimeout(60_000) { coordinator().download(
                    request = DownloadRequest.Http("retry-honoured", server.url, destination),
                    destination = destination,
                    partial = DefaultFileStorage().getPartialFile(destination).toPath(),
                    connections = 4,
                ) {} }
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue(outcome is HttpDownloadCoordinator.Outcome.Success, "download should succeed after honouring the hint: $outcome")
            assertTrue(elapsed >= 2_000, "the retry fired before the 2s Retry-After elapsed (only ${elapsed}ms)")
        }
    }

    @Test
    fun `without a hint a flaky 500 still gets an exponential wait between chunk retries`() {
        // 8 MiB, one worker, three injected 500s on the first data-plane requests. Each failure
        // advances the chunk attempt counter, so the wait windows grow: 0..500, 0..1000, 0..2000.
        // The sum has a floor at 0 (full jitter picks uniformly from `[0, window]`), but across
        // three retries we should still see the download stretch noticeably beyond what a
        // no-backoff retry loop would take. Set the bar at 700ms - well below the ~3500ms ceiling
        // (0.5+1+2) but well above the ~200ms an unhinted hammer would finish in on loopback.
        val payload = TestHttpServer.of(8 * mib, seed = 1234)
        TestHttpServer(payload, keepAlive = true).use { server ->
            server.failStatus = HttpStatusCode.InternalServerError.value
            server.failFirstN = 3
            // No `server.retryAfter` - engine falls back to its own exponential schedule.
            val destination = dir.resolve("no-hint-backoff.bin")
            val started = System.currentTimeMillis()
            val outcome = runBlocking {
                withTimeout(60_000) { coordinator().download(
                    request = DownloadRequest.Http("no-hint-backoff", server.url, destination),
                    destination = destination,
                    partial = DefaultFileStorage().getPartialFile(destination).toPath(),
                    connections = 4,
                ) {} }
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue(outcome is HttpDownloadCoordinator.Outcome.Success, "download should recover after three 500s: $outcome")
            assertTrue(elapsed >= 700, "three retries with exponential backoff finished in only ${elapsed}ms - no wait was applied")
        }
    }

    // ---------------------------------------------------------- coordinator threading

    @Test
    fun `Outcome Failure carries the Retry-After hint up so the task scheduler can widen its own wait`() {
        // Payload small enough to fit in one chunk. Server always 503 with a 6-second Retry-After.
        // Chunk budget exhausts after MAX_CHUNK_ATTEMPTS; the Outcome.Failure must still surface
        // the last-seen hint so DownloadExecutorImpl can widen the task-level retry schedule.
        val payload = TestHttpServer.of(1 * mib, seed = 77)
        TestHttpServer(payload, keepAlive = true).use { server ->
            server.failStatus = HttpStatusCode.ServiceUnavailable.value
            server.failFirstN = 100          // exhaust the per-chunk retry budget
            server.retryAfter = "2"
            val destination = dir.resolve("always-503.bin")
            val outcome = runBlocking {
                withTimeout(60_000) { coordinator().download(
                    request = DownloadRequest.Http("always-503", server.url, destination),
                    destination = destination,
                    partial = DefaultFileStorage().getPartialFile(destination).toPath(),
                    connections = 1,
                ) {} }
            }
            val failure = outcome as HttpDownloadCoordinator.Outcome.Failure
            assertEquals(2_000L, failure.retryAfterMillis, "the last-seen Retry-After must reach the coordinator's Outcome")
        }
    }

    // ---------------------------------------------------------- helpers

    private fun coordinator(): HttpDownloadCoordinator = HttpDownloadCoordinator(
        client = HttpNetworkClient(client),
        storage = DefaultFileStorage(),
        limiter = BandwidthLimiter(),
        settings = DownloadSettings(
            httpChunkSizeMb = 1,
            httpMinParallelSizeBytes = 1,
        ),
    )

    private fun headersOf(vararg pairs: Pair<String, String>): Headers {
        val builder = HeadersBuilder()
        for ((k, v) in pairs) builder.append(k, v)
        return builder.build()
    }
}
