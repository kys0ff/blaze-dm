package org.blaze.engine.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRetryLogicTest {

    @Test
    fun `test auto retry logic retries exactly maxRetries times`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-retry-logic-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val maxRetries = 3
        settingsRepo.updateSettings { 
            it.copy(
                autoRetryFailed = true,
                maxRetries = maxRetries,
                retryDelaySeconds = 0 // Immediate retry for test
            )
        }

        val mockEngine = MockEngine { _ ->
            respond("Error", status = HttpStatusCode.InternalServerError)
        }
        val httpClient = HttpClient(mockEngine)
        val storage = DefaultFileStorage()

        var executionCount = 0
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            storage = storage,
            httpClient = httpClient,
            executorFactory = null // Use real DownloadExecutorImpl
        )
        
        val id = manager.enqueue(DownloadRequest.Http("RetryLogicTest", "http://fail", Path.of("test.txt")))
        manager.start(id)

        // Wait for all retries to happen. 
        // Initial attempt (0) + 3 retries = 4 attempts total.
        
        var finalTask: DownloadTask? = null
        for (i in 1..200) {
            finalTask = manager.getTask(id)
            // We are looking for it to eventually reach retryCount 3 and then stay Failed
            if (finalTask?.state == DownloadState.Failed && finalTask.retryCount == maxRetries) {
                // Wait a bit more to ensure no more retries happen
                delay(200.milliseconds)
                break
            }
            delay(50.milliseconds)
        }

        finalTask = manager.getTask(id)
        assertEquals(maxRetries, finalTask?.retryCount, "Should have retried $maxRetries times")
        assertEquals(DownloadState.Failed, finalTask?.state, "Should end in Failed state after all retries")

        // Manual start should reset retryCount
        manager.start(id)
        delay(200.milliseconds)
        finalTask = manager.getTask(id)
        assertEquals(0, finalTask?.retryCount, "Manual start should reset retryCount to 0")

        manager.shutdown()
        tempDir.toFile().deleteRecursively()
    }
}
