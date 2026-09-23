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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the one answer the interface layer cannot derive from state alone: whether the *source*
 * will let an interrupted transfer be continued.
 *
 * The engine is the only component that ever sees a range being honoured or refused, so what it says
 * here is the difference between a GUI that warns truthfully and one that guesses. Both directions
 * are tested, plus the case where it must say nothing at all.
 */
class HttpRangeSupportReportingTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-range-report")

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

    private val accelerated = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)

    /** Runs a download, collecting every range-support answer the coordinator emitted, in order. */
    private fun runReporting(
        name: String,
        url: String,
        settings: DownloadSettings,
        connections: Int,
        seedPartialWith: ByteArray? = null
    ): Pair<HttpDownloadCoordinator.Outcome, List<Boolean>> {
        val destination = dir.resolve(name)
        val partial = storage.getPartialFile(destination).toPath()
        if (seedPartialWith != null) Files.write(partial, seedPartialWith)

        val answers = CopyOnWriteArrayList<Boolean>()
        val outcome = runBlocking {
            HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                settings = settings
            ).download(
                request = DownloadRequest.Http(name = name, url = url, destination = destination),
                destination = destination,
                partial = partial,
                connections = connections,
                onRangeSupport = { answers += it }
            ) {}
        }
        if (outcome is HttpDownloadCoordinator.Outcome.Success) storage.move(partial, destination)
        return outcome to answers
    }

    @Test
    fun `a source that proves ranged reads is reported as resumable`() {
        val payload = TestHttpServer.of(8 * mib, seed = 901)
        TestHttpServer(payload).use { server ->
            val (outcome, answers) = runReporting("provable.bin", server.url, accelerated, connections = 4)

            assertIsSuccess(outcome)
            assertEquals(listOf(true), answers, "exactly one proven answer is expected")
        }
    }

    @Test
    fun `a resume range that the server ignores is reported as unresumable`() {
        val payload = TestHttpServer.of(8 * mib, seed = 902)
        // A contiguous partial from an earlier run, so the resume really does ask for a range.
        val alreadyHave = payload.copyOfRange(0, 2 * mib)
        TestHttpServer(payload, supportsRanges = false).use { server ->
            val singleStream = DownloadSettings(httpAccelerationEnabled = false)
            val (outcome, answers) = runReporting("ignored.bin", server.url, singleStream, 1, alreadyHave)

            assertIsSuccess(outcome)
            assertEquals(
                listOf(false),
                answers,
                "being served the whole body in answer to a range is the clearest refusal there is"
            )
            // The refusal must not cost the file: the run restarts and still lands byte-exact.
            assertBytes(payload, dir.resolve("ignored.bin"))
        }
    }

    @Test
    fun `a source that never answers the range question is not reported at all`() {
        val payload = TestHttpServer.of(8 * mib, seed = 903)
        TestHttpServer(
            payload,
            supportsRanges = false,
            acceptRangesHeader = null
        ).use { server ->
            val (outcome, answers) = runReporting("silent.bin", server.url, accelerated, connections = 4)

            assertIsSuccess(outcome)
            assertTrue(
                answers.isEmpty(),
                "an unasked question must not become a warning: the UI has to stay silent on $answers"
            )
            assertBytes(payload, dir.resolve("silent.bin"))
        }
    }

    @Test
    fun `a source that refuses ranges outright is reported as unresumable before any byte moves`() {
        val payload = TestHttpServer.of(8 * mib, seed = 904)
        TestHttpServer(payload, acceptRangesHeader = "none").use { server ->
            val (outcome, answers) = runReporting("refused.bin", server.url, accelerated, connections = 4)

            assertIsSuccess(outcome)
            assertEquals(listOf(false), answers)
        }
    }

    private fun assertIsSuccess(outcome: HttpDownloadCoordinator.Outcome) {
        assertTrue(
            outcome is HttpDownloadCoordinator.Outcome.Success,
            "the run should have completed, got $outcome"
        )
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
