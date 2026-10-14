package org.blaze.engine.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.execution.HttpDownloadCoordinator
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.PayloadGenerator
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Phase 3.5 - engine-choice benchmark.
 *
 * PROMPT.md §Phase-3 asks us to "pick and justify the best Ktor engine for throughput by
 * benchmarking CIO, OkHttp, and Java engines". This is that measurement. Everything else in
 * Phase 3 is built on the winner, so the recipe is deliberately conservative:
 *
 *  - Same 32 MiB LCG payload the HTTP baseline uses, so the two data sets can be read side by side.
 *  - Same connection ladder (1, 4, 8, 16) and same unthrottled / 4 MiB-s-per-conn scenarios.
 *  - Same timeouts, same `followRedirects = false`, everything else identical except the engine.
 *  - OkHttp is only on the test classpath; if it wins, promoting it to a runtime dep is a
 *    separate decision the reviewer signs off on.
 *
 * Skipped unless `-Dblaze.benchmark=true` (`:download-engine:httpBenchmark`). The numbers are
 * printed to stdout and pasted into `docs/PROGRESS.md` for the Phase 3 commit that changes the
 * engine choice.
 */
class HttpEngineBenchmark {

    private val enabled get() = System.getProperty("blaze.benchmark") == "true"
    private val dir: Path = Files.createTempDirectory("blaze-engine-bench")
    private val storage = DefaultFileStorage()
    private val mib = PayloadGenerator.MIB

    private data class EngineUnderTest(val name: String, val factory: () -> HttpClient)

    @Test
    fun `CIO vs Java vs OkHttp on the same segmented download workload`() {
        if (!enabled) return
        val payload = TestHttpServer.of(32 * mib, seed = 0xE5915E5)

        val engines = listOf(
            EngineUnderTest("Java")   { HttpClient(Java)   { applyTimeouts() } },
            EngineUnderTest("CIO")    { HttpClient(CIO)    { applyTimeouts() } },
            EngineUnderTest("OkHttp") { HttpClient(OkHttp) { applyTimeouts() } },
        )

        val report = StringBuilder()
        report.appendLine("=== Ktor engine comparison (payload=32 MiB, warmups=1, samples=3) ===")

        for (engine in engines) {
            val client = engine.factory()
            try {
                for (connections in listOf(1, 4, 8, 16)) {
                    report.appendLine(runScenario(engine.name, "unthrottled", payload, connections, 0L, client).summary.render())
                    report.appendLine(runScenario(engine.name, "throttled-4MiBps", payload, connections, 4L * mib, client).summary.render())
                }
            } finally {
                client.close()
            }
        }

        println(report)
    }

    /**
     * Mirrors `DownloadManager.createDefaultHttpClient()` exactly (infinite request timeout,
     * 15 s connect, 60 s socket, no auto-redirect) so the only variable across runs is the engine.
     */
    private fun io.ktor.client.HttpClientConfig<*>.applyTimeouts() {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        followRedirects = false
    }

    private class ScenarioResult(val summary: BenchmarkRunner.Summary, val bytesServed: Long, val connectionsOpened: Int)

    private fun runScenario(
        engineName: String,
        scenario: String,
        payload: ByteArray,
        connections: Int,
        perConnectionBytesPerSec: Long,
        client: HttpClient,
    ): ScenarioResult {
        val label = "$engineName | $scenario | conn=$connections"
        var lastServed = 0L
        var lastConns = 0
        val summary = BenchmarkRunner.run(label = label, warmups = 1, samples = 3) { index ->
            val timer = BenchmarkRunner.timer(label, payload.size.toLong())
            TestHttpServer(payload, perConnectionBytesPerSec = perConnectionBytesPerSec, keepAlive = true).use { server ->
                val destination = dir.resolve("engine-$engineName-$scenario-$index-$connections.bin")
                val partial = storage.getPartialFile(destination).toPath()
                val coordinator = HttpDownloadCoordinator(
                    client = HttpNetworkClient(client),
                    storage = storage,
                    limiter = BandwidthLimiter(),
                    settings = DownloadSettings(httpChunkSizeMb = 1, httpMinParallelSizeBytes = 1),
                )
                val outcome = runBlocking {
                    coordinator.download(
                        request = DownloadRequest.Http("engine-$index", server.url, destination),
                        destination = destination,
                        partial = partial,
                        connections = connections,
                    ) {}
                }
                Files.deleteIfExists(partial)
                Files.deleteIfExists(destination)
                lastServed = server.bytesServed.get()
                lastConns = server.connectionCount.get()
                timer.markFirstByte()
                check(outcome is HttpDownloadCoordinator.Outcome.Success) { "$label failed: $outcome" }
            }
            timer.stop(extra = mapOf("bytesServed" to lastServed, "serverConnections" to lastConns))
        }
        return ScenarioResult(summary, lastServed, lastConns)
    }
}
