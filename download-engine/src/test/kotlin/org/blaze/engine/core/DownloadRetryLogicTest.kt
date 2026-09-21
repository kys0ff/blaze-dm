package org.blaze.engine.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadRetryLogicTest {

    @Test
    fun `test auto retry logic retries exactly maxRetries times`() {
        // The retry flow drives the real DownloadExecutorImpl through a real HttpClient,
        // whose requests are served on an I/O thread. Running the manager on a real
        // dispatcher (instead of a virtual-time test dispatcher) keeps the wall-clock and
        // the network round-trips consistent so the observable states are deterministic.
        val tempDir = Files.createTempDirectory("blaze-retry-logic-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val maxRetries = 3
        val mockEngine = MockEngine { _ ->
            respond("Error", status = HttpStatusCode.InternalServerError)
        }
        val httpClient = HttpClient(mockEngine)
        val storage = DefaultFileStorage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadManager(
            scope = scope,
            repository = repository,
            settingsRepository = settingsRepo,
            storage = storage,
            httpClient = httpClient,
            executorFactory = null // Use real DownloadExecutorImpl
        )

        try {
            runBlocking {
                settingsRepo.updateSettings {
                    it.copy(
                        autoRetryFailed = true,
                        maxRetries = maxRetries,
                        retryDelaySeconds = 0 // Immediate retry for test
                    )
                }

                val id = manager.enqueue(DownloadRequest.Http("RetryLogicTest", "http://fail", Path.of("test.txt")))
                manager.start(id)

                // Wait for all retries to happen.
                // Initial attempt (0) + 3 retries = 4 attempts total.
                var finalTask: DownloadTask? = null
                withTimeout(15.seconds) {
                    while (true) {
                        finalTask = manager.getTask(id)
                        if (finalTask?.state == DownloadState.Failed && finalTask.retryCount == maxRetries) {
                            break
                        }
                        delay(50.milliseconds)
                    }
                }

                // Give a small buffer to ensure no further retries are scheduled past maxRetries.
                delay(300.milliseconds)
                finalTask = manager.getTask(id)
                assertEquals(maxRetries, finalTask?.retryCount, "Should have retried $maxRetries times")
                assertEquals(DownloadState.Failed, finalTask?.state, "Should end in Failed state after all retries")

                // Manual start should reset retryCount. Disable auto-retry so the reset
                // value is observable and not immediately climbed back up by new retries.
                settingsRepo.updateSettings { it.copy(autoRetryFailed = false) }
                manager.start(id)
                delay(200.milliseconds)
                finalTask = manager.getTask(id)
                assertEquals(0, finalTask?.retryCount, "Manual start should reset retryCount to 0")

                manager.shutdown()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
