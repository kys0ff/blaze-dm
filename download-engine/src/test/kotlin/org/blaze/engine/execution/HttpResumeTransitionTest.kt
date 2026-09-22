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
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * State-model coverage for resuming a *segmented* (sparse, preallocated) partial under different
 * transitions, which the previous pass flagged as safe-but-wasteful and left unresolved.
 *
 * The invariant every scenario defends: the finished file is always byte-exact. Either the engine
 * reuses the chunks the sidecar can vouch for, or it starts clean - but it never ships a holey or
 * shifted partial, and it never fails a resumable download just because the transfer mode changed.
 */
class HttpResumeTransitionTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-resume")

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

    private fun run(url: String, name: String, settings: DownloadSettings, connections: Int): HttpDownloadCoordinator.Outcome {
        val destination = dir.resolve(name)
        val partial = storage.getPartialFile(destination).toPath()
        val outcome = runBlocking {
            coordinator(settings).download(
                request = DownloadRequest.Http(name = name, url = url, destination = destination),
                destination = destination,
                partial = partial,
                connections = connections
            ) {}
        }
        if (outcome is HttpDownloadCoordinator.Outcome.Success) storage.move(partial, destination)
        return outcome
    }

    private val accelerated = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)

    /** Runs a segmented download until it fails at [failAfterBytes], leaving a sidecar + sparse partial. */
    private fun leaveSegmentedPartial(server: TestHttpServer, name: String, failAfterBytes: Long) {
        server.earlyCloseAfterBytes = failAfterBytes
        assertIs<HttpDownloadCoordinator.Outcome.Failure>(run(server.url, name, accelerated, connections = 4))
        val destination = dir.resolve(name)
        assertTrue(
            Files.exists(storage.getResumeStateFile(destination).toPath()),
            "the failing segmented run should have left a sidecar"
        )
    }

    // ---------------------------------------------------------------- segmented -> restart

    @Test
    fun `a crashed segmented partial resumes and reuses finished chunks`() {
        val payload = TestHttpServer.of(16 * mib, seed = 301)
        TestHttpServer(payload).use { server ->
            leaveSegmentedPartial(server, "resumable.bin", failAfterBytes = 8L * mib)

            server.earlyCloseAfterBytes = Long.MAX_VALUE
            val before = server.bytesServed.get()
            val outcome = run(server.url, "resumable.bin", accelerated, connections = 4)
            val served = server.bytesServed.get() - before

            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            assertBytes(payload, dir.resolve("resumable.bin"))
            assertTrue(served < payload.size, "the resume re-read the whole file: $served of ${payload.size} bytes")
        }
    }

    // ---------------------------------------------------------------- segmented -> single stream

    @Test
    fun `switching to a single connection still resumes a chunked partial from its sidecar`() {
        val payload = TestHttpServer.of(16 * mib, seed = 302)
        TestHttpServer(payload).use { server ->
            leaveSegmentedPartial(server, "modeswitch.bin", failAfterBytes = 8L * mib)

            server.earlyCloseAfterBytes = Long.MAX_VALUE
            val before = server.bytesServed.get()
            // Acceleration is now OFF and only one connection is asked for: a naive single-stream
            // resume keys off the (preallocated, whole-length) partial size and either 416-fails or
            // trusts holes. The sidecar must win instead, and reuse the chunks already on disk.
            val singleStream = DownloadSettings(httpAccelerationEnabled = false)
            val outcome = run(server.url, "modeswitch.bin", singleStream, connections = 1)
            val served = server.bytesServed.get() - before

            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            assertBytes(payload, dir.resolve("modeswitch.bin"))
            assertTrue(served < payload.size, "single-stream resume re-fetched everything: $served bytes")
        }
    }

    // ---------------------------------------------------------------- damaged partial

    @Test
    fun `a partial truncated below its sidecar length is restarted, never resumed into holes`() {
        val payload = TestHttpServer.of(16 * mib, seed = 303)
        TestHttpServer(payload).use { server ->
            leaveSegmentedPartial(server, "damaged.bin", failAfterBytes = 10L * mib)

            // Corrupt the partial after the fact: shrink it below the length the sidecar describes.
            // Resuming "trusted" chunks now would leave a permanent hole in the finished file.
            val partial = storage.getPartialFile(dir.resolve("damaged.bin")).toPath()
            RandomAccessFile(partial.toFile(), "rw").use { it.setLength(3L * mib) }

            server.earlyCloseAfterBytes = Long.MAX_VALUE
            val before = server.bytesServed.get()
            val outcome = run(server.url, "damaged.bin", accelerated, connections = 4)
            val served = server.bytesServed.get() - before

            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            assertBytes(payload, dir.resolve("damaged.bin"))
            assertTrue(
                served >= payload.size - 1024,
                "the damaged state was trusted; only $served bytes were re-read (holes would remain)"
            )
        }
    }

    // ---------------------------------------------------------------- sidecar lost, sparse partial

    @Test
    fun `a sparse partial with no sidecar never finalises as an all-zeros file`() {
        val payload = TestHttpServer.of(12 * mib, seed = 304)
        TestHttpServer(payload).use { server ->
            val name = "lostsidelcar.bin"
            val destination = dir.resolve(name)
            val partial = storage.getPartialFile(destination).toPath()
            // Simulate a segmented run whose sidecar vanished: the file is preallocated to the full
            // length but the tail was never written (reads back as zero). There is no sidecar to
            // tell us the truth, so a size-based single-stream resume must NOT declare it complete.
            RandomAccessFile(partial.toFile(), "rw").use { raf ->
                raf.setLength(12L * mib) // whole length, but mostly a hole
                raf.seek(0)
                raf.write(payload, 0, 2 * mib) // only a 2 MiB head is real
            }

            val outcome = run(server.url, name, DownloadSettings(httpAccelerationEnabled = false), connections = 1)

            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            assertBytes(payload, destination)
        }
    }

    // ---------------------------------------------------------------- validator changed

    @Test
    fun `resume state whose validator changed is rejected and the file re-downloaded`() {
        val first = TestHttpServer.of(16 * mib, seed = 305)
        val name = "rotated.bin"
        TestHttpServer(first, validator = "\"v1\"").use { server ->
            leaveSegmentedPartial(server, name, failAfterBytes = 8L * mib)
        }
        val second = TestHttpServer.of(16 * mib, seed = 306)
        TestHttpServer(second, validator = "\"v2\"").use { server ->
            val outcome = run(server.url, name, accelerated, connections = 4)
            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            assertBytes(second, dir.resolve(name))
            assertTrue(server.bytesServed.get() >= second.size - 1024, "a stale-validator resume reused old chunks")
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
