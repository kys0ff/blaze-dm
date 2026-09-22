package org.blaze.engine.execution

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the HTTP data path against a real socket: what lands on disk must be
 * byte-identical to what the server holds, whatever the server does in between.
 *
 * These are regression tests for the corruption bugs found in the audit - a server that ignores
 * `Range`, a body that ends early, and a resume that re-reads chunks it already has.
 */
class HttpSegmentedTransferTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-transfer")

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        followRedirects = false
    }

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private fun coordinator(settings: DownloadSettings) = HttpDownloadCoordinator(
        client = HttpNetworkClient(client),
        storage = storage,
        limiter = BandwidthLimiter(),
        settings = settings
    )

    private class Run(
        val outcome: HttpDownloadCoordinator.Outcome,
        val destination: Path,
        val progress: List<Long>
    )

    /** Drives the coordinator the way [org.blaze.engine.execution.DownloadExecutorImpl] does. */
    private suspend fun run(
        url: String,
        name: String,
        settings: DownloadSettings = DownloadSettings(),
        connections: Int = 4,
        seedPartial: suspend (Path) -> Unit = {}
    ): Run {
        val destination = dir.resolve(name)
        val partial = storage.getPartialFile(destination).toPath()
        seedPartial(partial)
        val progress = ArrayList<Long>()
        val outcome = coordinator(settings).download(
            request = DownloadRequest.Http(name = name, url = url, destination = destination),
            destination = destination,
            partial = partial,
            connections = connections
        ) { progress += it.downloadedBytes }
        if (outcome is HttpDownloadCoordinator.Outcome.Success) storage.move(partial, destination)
        return Run(outcome, destination, progress)
    }

    // --------------------------------------------------------------------------- happy paths

    @Test
    fun `a segmented transfer produces a byte exact file using several connections`(): Unit = runBlocking {
        val payload = TestHttpServer.of(16 * mib, seed = 7)
        TestHttpServer(payload).use { server ->
            val result = run(server.url, "segmented.bin", connections = 4)

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertEquals(16L * mib, result.outcome.totalBytes)
            assertBytes(payload, result.destination)
            assertEquals(16L * mib, Files.size(result.destination))

            val distinct = server.rangeRequests.filter { it.isNotEmpty() }.distinct()
            assertTrue(distinct.size >= 4, "only ${distinct.size} ranges were used; acceleration is dead")
        }
    }

    @Test
    fun `a file below the parallel threshold stays on the single stream`(): Unit = runBlocking {
        val payload = TestHttpServer.of(mib, seed = 11)
        TestHttpServer(payload).use { server ->
            val result = run(server.url, "small.bin", connections = 8)

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertBytes(payload, result.destination)
            // Just the capability probe (bytes=0-0) may ask for a range; the body must not be split.
            val ranged = server.rangeRequests.filter { it.isNotEmpty() && it != "bytes=0-0" }
            assertEquals(emptyList(), ranged, "a 1 MiB file should not have been segmented")
        }
    }

    // --------------------------------------------------------------------------- corruption guards

    @Test
    fun `a server that ignores ranges never writes shifted bytes`(): Unit = runBlocking {
        val payload = TestHttpServer.of(12 * mib, seed = 21)
        TestHttpServer(payload, supportsRanges = false).use { server ->
            val name = "liar.bin"
            val destination = dir.resolve(name)
            // A stale partial from an earlier session: exactly what used to be appended to.
            Files.createDirectories(destination.parent)

            val result = run(server.url, name, connections = 4) {
                Files.write(it, ByteArray(4 * mib) { 0x7F })
            }

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertBytes(payload, destination)
            assertTrue(
                server.bytesServed.get() >= payload.size - 1024,
                "the whole body should have been re-read, got ${server.bytesServed.get()}"
            )
        }
    }

    @Test
    fun `an early eof is a failure and never a completed file`(): Unit = runBlocking {
        val payload = TestHttpServer.of(8 * mib, seed = 33)
        TestHttpServer(payload, supportsRanges = false, earlyCloseAfterBytes = 2L * mib).use { server ->
            val result = run(server.url, "early.bin", connections = 1)

            assertIs<HttpDownloadCoordinator.Outcome.Failure>(result.outcome)
            assertFalse(Files.exists(result.destination), "a truncated body must not be finalised")
        }
    }

    @Test
    fun `a few dropped connections are recovered inside one transfer`(): Unit = runBlocking {
        // Six connections over sixteen 1 MiB chunks, with the server cutting the first three bodies
        // it serves. A chunk lost that way has to go back into the work queue and be finished by
        // another connection; it must not fail the download or leave a hole in the file.
        val payload = TestHttpServer.of(16 * mib, seed = 71)
        TestHttpServer(payload, cutFirstBodies = 3).use { server ->
            val result = run(
                url = server.url,
                name = "flaky.bin",
                settings = DownloadSettings(httpChunkSizeMb = 1),
                connections = 6
            )

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertBytes(payload, result.destination)
            assertTrue(
                server.requestCount.get() >= 19,
                "the cut chunks were never re-read (${server.requestCount.get()} requests for 16 chunks)"
            )
            assertEquals(16L * mib, result.progress.last(), "progress drifted off the real byte count")
        }
    }

    @Test
    fun `resume state that no longer matches the server is discarded`(): Unit = runBlocking {
        val first = TestHttpServer.of(16 * mib, seed = 41)
        val name = "swapped.bin"
        val destination = dir.resolve(name)

        // Fail halfway through the first file so a chunk bitset survives on disk.
        TestHttpServer(first, earlyCloseAfterBytes = 6L * mib, validator = "\"one\"").use { server ->
            assertIs<HttpDownloadCoordinator.Outcome.Failure>(
                run(server.url, name, connections = 4).outcome
            )
            assertTrue(
                Files.exists(storage.getResumeStateFile(destination).toPath()),
                "the sidecar should exist after a failed segmented attempt"
            )
        }

        // Same name, different bytes and a different ETag: the old chunks are worthless.
        val second = TestHttpServer.of(16 * mib, seed = 42)
        TestHttpServer(second, validator = "\"two\"").use { server ->
            val result = run(server.url, name, connections = 4)

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertBytes(second, destination)
            assertTrue(
                server.bytesServed.get() >= second.size - 1024,
                "stale chunks were reused: only ${server.bytesServed.get()} bytes were served"
            )
        }
    }

    // --------------------------------------------------------------------------- resume

    @Test
    fun `a resumed segmented transfer does not re-read finished chunks`(): Unit = runBlocking {
        val payload = TestHttpServer.of(16 * mib, seed = 51)
        TestHttpServer(payload, earlyCloseAfterBytes = 8L * mib).use { server ->
            val name = "resumed.bin"
            val destination = dir.resolve(name)

            assertIs<HttpDownloadCoordinator.Outcome.Failure>(run(server.url, name, connections = 4).outcome)
            val servedFirst = server.bytesServed.get()
            assertTrue(servedFirst > 0, "nothing was transferred before the failure")

            server.earlyCloseAfterBytes = Long.MAX_VALUE
            val before = server.bytesServed.get()
            val result = run(server.url, name, connections = 4)

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertBytes(payload, destination)

            val servedSecond = server.bytesServed.get() - before
            assertTrue(
                servedSecond < payload.size,
                "the resume re-read the whole file: $servedSecond of ${payload.size} bytes " +
                    "($servedFirst served in the first attempt)"
            )
            assertFalse(
                Files.exists(storage.getResumeStateFile(destination).toPath()),
                "the sidecar must not outlive a finished download"
            )
        }
    }

    @Test
    fun `progress reaches the full size of the file`(): Unit = runBlocking {
        // Throttled so the transfer outlives the progress interval; a loopback-fast download
        // legitimately finishes between two ticks.
        val payload = TestHttpServer.of(6 * mib, seed = 61)
        TestHttpServer(payload, perConnectionBytesPerSec = 2L * 1024 * 1024).use { server ->
            val result = run(server.url, "progress.bin", connections = 3)

            assertIs<HttpDownloadCoordinator.Outcome.Success>(result.outcome)
            assertTrue(result.progress.size > 1, "no intermediate progress was reported: ${result.progress}")
            assertTrue(result.progress.first() < payload.size, "progress jumped straight to the end")
            assertEquals(payload.size.toLong(), result.progress.last())
        }
    }

    /**
     * Compares large files without [assertContentEquals]'s failure message, which renders every
     * element and needs more heap than the test JVM has for a ten-megabyte mismatch.
     */
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
