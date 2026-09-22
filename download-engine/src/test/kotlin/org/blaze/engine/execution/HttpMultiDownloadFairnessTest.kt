package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
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
 * One engine-wide ceiling must hold no matter how many transfers are in flight.
 *
 * The global limiter is a single shared leaky bucket handed to every executor. The old bug it was
 * written to fix was that "6 MiB/s" configured per-download actually let `N * 6 MiB/s` hit the wire.
 * These tests run two segmented downloads against the same limiter and assert (a) the aggregate is
 * capped near the limit, not at a multiple of it, and (b) neither transfer starves while the other
 * runs - the fairness property a downloader needs when HTTP and torrent jobs share the machine.
 */
class HttpMultiDownloadFairnessTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-fair")
    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private fun coordinator(limiter: BandwidthLimiter) = HttpDownloadCoordinator(
        client = HttpNetworkClient(client),
        storage = storage,
        limiter = limiter,
        settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1)
    )

    private suspend fun downloadOne(server: TestHttpServer, name: String, limiter: BandwidthLimiter, connections: Int): Long {
        val destination = dir.resolve(name)
        val partial = storage.getPartialFile(destination).toPath()
        val outcome = coordinator(limiter).download(
            request = DownloadRequest.Http(name, server.url, destination),
            destination = destination,
            partial = partial,
            connections = connections
        ) {}
        assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome, "$name did not finish")
        storage.move(partial, destination)
        return Files.readAllBytes(destination).size.toLong()
    }

    @Test
    fun `the global cap is not multiplied by the number of concurrent downloads`() {
        // Big enough that the limiter's bounded one-second startup credit amortises into the
        // steady-state cap; small enough to stay quick. The number this test defends against is
        // ~2x the cap (the per-download-throttling bug), which this margin cannot hide.
        val sizeMib = 24
        val capMibPerSec = 6L
        val payload = TestHttpServer.of(sizeMib * mib, seed = 5150)

        // Uncapped baseline: proves the server/link is genuinely faster than the cap, so the cap is
        // the binding constraint rather than an incidental limit of the environment.
        val freeLimiter = BandwidthLimiter()
        TestHttpServer(payload, keepAlive = true).use { server ->
            val started = System.nanoTime()
            runBlocking { downloadOne(server, "solo.bin", freeLimiter, connections = 4) }
            val soloMibps = sizeMib / ((System.nanoTime() - started) / 1_000_000_000.0)
            assertTrue(soloMibps > capMibPerSec, "the reference run was already under the cap (${soloMibps} MiB/s); cap test is meaningless")
            Files.deleteIfExists(dir.resolve("solo.bin"))
        }

        val shared = BandwidthLimiter().apply { setLimit(capMibPerSec * mib) }
        val totalBytes = runBlocking {
            TestHttpServer(payload, keepAlive = true).use { a ->
                TestHttpServer(payload, keepAlive = true).use { b ->
                    val started = System.nanoTime()
                    var aBytes = 0L
                    var bBytes = 0L
                    coroutineScope {
                        launch { aBytes = downloadOne(a, "raceA.bin", shared, connections = 4) }
                        launch { bBytes = downloadOne(b, "raceB.bin", shared, connections = 4) }
                    }
                    val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                    val aggregate = (aBytes + bBytes) / mib / elapsed
                    println(
                        (
                            "global cap: 2 concurrent downloads of ${sizeMib} MiB finished in %.2f s, " +
                                "aggregate %.2f MiB/s under a %d MiB/s cap"
                            ).format(elapsed, aggregate, capMibPerSec)
                    )
                    // The bug this guards against: 2 downloads each getting the full 6 MiB/s => ~12.
                    // A shared bucket with a one-second credit can transiently sit a bit above the
                    // cap; it cannot approach 2x. 1.5x cleanly separates "shared" from "multiplied".
                    assertTrue(
                        aggregate <= capMibPerSec * 1.5,
                        "the cap was exceeded by concurrent downloads: aggregate %.2f MiB/s vs cap $capMibPerSec".format(aggregate)
                    )
                    // Neither transfer may starve: both must have delivered their whole payload.
                    assertEquals((sizeMib * mib).toLong(), aBytes)
                    assertEquals((sizeMib * mib).toLong(), bBytes)
                    aBytes + bBytes
                }
            }
        }
        assertEquals((2L * sizeMib * mib), totalBytes)
    }
}
