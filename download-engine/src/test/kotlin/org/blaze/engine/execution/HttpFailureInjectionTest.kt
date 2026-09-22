package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.metrics.EngineMetrics
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.storage.FileStorage
import org.blaze.engine.storage.PositionedWriter
import org.blaze.engine.support.TestHttpServer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Non-graceful failure coverage for the paths this pass introduced or touched.
 *
 * The transfer/recovery tests all exercise the happy "the server misbehaves but the engine copes"
 * route. These inject failures *underneath* the coordinator - a disk that stops accepting writes
 * mid-chunk, and a user cancel that lands while a worker is parked on a stalled socket - and pin the
 * guarantees that matter most for a downloader: a failed transfer never masquerades as a complete
 * file, and cancelling a stalled download actually returns instead of waiting for the socket.
 */
class HttpFailureInjectionTest {

    private val mib = 1024 * 1024
    private val dir: Path = Files.createTempDirectory("blaze-http-fault")
    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    /** A storage whose positioned writes start failing once [failAfterBytes] have been committed. */
    private class WriteFailureStorage(private val delegate: FileStorage, private val failAfterBytes: Long) :
        FileStorage by delegate {
        val written = AtomicLong(0)

        override fun openPositionedWriter(path: Path): PositionedWriter {
            val real = delegate.openPositionedWriter(path)
            return object : PositionedWriter {
                override fun writeAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
                    if (written.addAndGet(length.toLong()) > failAfterBytes) {
                        throw IOException("No space left on device")
                    }
                    real.writeAt(position, buffer, offset, length)
                }

                override fun force(meta: Boolean) = real.force(meta)
                override fun size(): Long = real.size()
                override fun close() = real.close()
            }
        }

        override fun getResumeStateFile(destination: Path): File = delegate.getResumeStateFile(destination)
    }

    @Test
    fun `a disk write failure mid-segment fails the transfer instead of finalising a corrupt file`() {
        val payload = TestHttpServer.of(24 * mib, seed = 8888)
        val storage = WriteFailureStorage(DefaultFileStorage(), failAfterBytes = 4L * mib)
        TestHttpServer(payload, keepAlive = true).use { server ->
            val name = "diskfull.bin"
            val destination = dir.resolve(name)
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)
            )
            val outcome = runBlocking {
                withTimeout(60_000) {
                    coordinator.download(
                        request = DownloadRequest.Http(name, server.url, destination),
                        destination = destination,
                        partial = partial,
                        connections = 4
                    ) {}
                }
            }

            val failure = assertIs<HttpDownloadCoordinator.Outcome.Failure>(outcome, "a full disk must not report success")
            assertEquals(DownloadError.DiskFull, failure.error, "the disk error should surface as DiskFull, not a retryable network blip")
            // The destination is only ever produced by a successful move; a failed transfer leaves it absent.
            assertTrue(Files.notExists(destination), "a corrupt/partial file was finalised despite the write failure")
        }
    }

    @Test
    fun `cancelling while a worker is parked on a stalled socket returns promptly, not after the hang`() {
        // The server accepts the request, sends headers, then goes silent for far longer than the
        // test's patience. The watchdog is deliberately disabled (stall threshold huge), so the ONLY
        // way this download can end is the coroutine cancellation reaching into the stalled JDK body
        // read. If cancellation were swallowed by the socket read, the join would never return.
        val payload = TestHttpServer.of(16 * mib, seed = 4321)
        TestHttpServer(payload, keepAlive = true).use { server ->
            server.hangFirstBodies = Int.MAX_VALUE
            server.hangMillis = 120_000

            val name = "cancelstall.bin"
            val destination = dir.resolve(name)
            val partial = storage().getPartialFile(destination).toPath()
            val metrics = EngineMetrics()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage(),
                limiter = BandwidthLimiter(),
                metrics = metrics,
                settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1, httpStalledConnectionSeconds = 3600)
            )

            val elapsed = runCatching {
                runBlocking {
                    measureCancellationLatency {
                        coordinator.download(
                            request = DownloadRequest.Http(name, server.url, destination),
                            destination = destination,
                            partial = partial,
                            connections = 3
                        ) {}
                    }
                }
            }

            // It must have been cancelled, and it must have returned quickly enough that we know the
            // stall was broken by cancellation rather than the 120 s server hang or the watchdog.
            val result = elapsed.getOrThrow()
            // On cancellation the block never returns an Outcome (a CancellationException propagates);
            // what matters is that it was NOT a Success and that the transfer is not left "complete".
            assertTrue(result.outcome !is HttpDownloadCoordinator.Outcome.Success, "a cancelled download reported success")
            assertTrue(result.cancelled, "the download completed instead of being cancelled: ${result.outcome}")
            assertTrue(result.latencyMillis < 30_000, "cancellation took ${result.latencyMillis} ms to unwind a stalled read")
            assertEquals(0L, metrics.snapshot().stalledWorkerDrops, "the watchdog, not cancellation, ended the transfer")
        }
    }

    private fun storage(): FileStorage = DefaultFileStorage()

    private class CancelResult(
        val outcome: HttpDownloadCoordinator.Outcome?,
        val cancelled: Boolean,
        val latencyMillis: Long
    )

    /**
     * Starts [block], lets it get going, cancels it, and reports how long the cancellation took. A
     * thrown [CancellationException] is reported as `cancelled = true`.
     */
    private suspend fun measureCancellationLatency(
        block: suspend () -> HttpDownloadCoordinator.Outcome
    ): CancelResult = withContext(Dispatchers.Default) {
        val job = Job()
        var outcome: HttpDownloadCoordinator.Outcome? = null
        var cancelled = false
        val launcher = launch(job) {
            try {
                outcome = block()
            } catch (e: CancellationException) {
                cancelled = true
            }
        }
        delay(1_500) // let a worker park on the stalled socket
        val started = System.currentTimeMillis()
        job.cancel()
        withTimeout(30_000) { launcher.join() }
        CancelResult(outcome, cancelled || outcome == null, System.currentTimeMillis() - started)
    }
}
