package org.blaze.engine.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression coverage for auto-retry when an HTTP download fails mid-stream with a
 * network timeout (the reported "network failure: request timeout has expired").
 */
class TimeoutRetryTest {

    private class StopObservingException : RuntimeException()

    /**
     * Server sends valid headers plus one tiny body chunk, then stalls while holding the
     * connection open. The real CIO client therefore fails mid-body via the socket
     * (read-inactivity) timeout - the same path a large, slow download hits in production.
     */
    private fun startStallingServer(): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        val port = server.localPort
        CoroutineScope(Dispatchers.IO).launch {
            while (!server.isClosed) {
                val socket: Socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                launch(Dispatchers.IO) {
                    try {
                        val out: OutputStream = socket.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Length: 100000000\r\n" +
                                "Connection: keep-alive\r\n\r\n").toByteArray()
                        )
                        out.write("partial".toByteArray())
                        out.flush()
                        delay(20_000.milliseconds) // never finish the body
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return server to port
    }

    @Test
    fun `manager auto retries and resumes after a mid-stream socket timeout`() {
        val tempDir = Files.createTempDirectory("blaze-midstream-retry")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)
        val (server, port) = startStallingServer()

        // Mirror the production client, but shrink the socket timeout so the test is fast.
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 1_500
                socketTimeoutMillis = 1_500
            }
            followRedirects = false
        }

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadManager(
            scope = scope,
            repository = repository,
            settingsRepository = settingsRepo,
            storage = DefaultFileStorage(),
            httpClient = httpClient,
            executorFactory = null
        )

        var sawQueuedRetry = false
        var sawRestartAfterRetry = false
        var downloadedOnRestart = 0L
        try {
            runBlocking {
                settingsRepo.updateSettings {
                    it.copy(autoRetryFailed = true, maxRetries = 5, retryDelaySeconds = 0)
                }
                val id = manager.enqueue(
                    DownloadRequest.Http("Mgr", "http://127.0.0.1:$port/file.bin", tempDir.resolve("out.bin"))
                )
                manager.start(id)

                withTimeout(20.seconds) {
                    try {
                        manager.observeTask(id).collect { t ->
                            if (t.state == DownloadState.Queued && t.retryCount >= 1) sawQueuedRetry = true
                            if (sawQueuedRetry && t.state.isActive && t.retryCount >= 1) {
                                sawRestartAfterRetry = true
                                downloadedOnRestart = t.downloadedBytes
                                throw StopObservingException()
                            }
                        }
                    } catch (_: StopObservingException) {
                        // expected: stop once the retry restart is confirmed
                    }
                }
            }
        } finally {
            runBlocking { manager.shutdown() }
            runBlocking { httpClient.close() }
            server.close()
            tempDir.toFile().deleteRecursively()
        }

        assertTrue(sawQueuedRetry, "A mid-stream timeout must schedule an auto-retry (Queued with retryCount >= 1)")
        assertTrue(sawRestartAfterRetry, "The scheduled retry must actually restart the download")
        // The resumed attempt must carry over the bytes downloaded before the failure.
        assertTrue(downloadedOnRestart > 0, "Retry should resume from previously downloaded bytes, was $downloadedOnRestart")
    }
}
