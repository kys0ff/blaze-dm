package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueConsistencyTest {

    private class MockExecutor(task: DownloadTask) : DownloadExecutor {
        private val _flow = MutableStateFlow<DownloadTask?>(null)
        private var finished = false
        
        init {
             _flow.value = task.copy(state = DownloadState.Downloading)
        }

        override fun execute(): Flow<DownloadTask> = channelFlow {
            _flow.collect { task ->
                if (task != null) {
                    send(task)
                    if (finished) close()
                }
            }
        }
        
        fun update(task: DownloadTask, finish: Boolean = false) {
            finished = finish
            _flow.value = task
        }

        fun complete() {
            update(_flow.value!!.copy(state = DownloadState.Completed), finish = true)
        }
    }

    @Test
    fun `test strict concurrency limit with retries`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-queue-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        // Limit to 2 concurrent downloads
        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 2) }

        val executors = mutableMapOf<String, MockExecutor>()

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = { task ->
                val exec = MockExecutor(task)
                executors[task.name] = exec
                exec
            }
        )

        // Enqueue 5 downloads. Note: DownloadManager.enqueue calls scheduler.enqueue
        // which launches processQueue. In runTest, we need to yield/delay for them to run.
        val ids = (1..5).map { i ->
            manager.enqueue(DownloadRequest.Http("File$i", "http://url$i", Path.of(".")))
        }

        // Give time for the launched processQueue calls
        delay(2000.milliseconds)

        // Verify only 2 are downloading
        val allTasks = ids.map { manager.getTask(it) }
        val activeStates = allTasks.map { it?.state }
        assertEquals(2, activeStates.count { it == DownloadState.Downloading || it == DownloadState.Starting }, 
            "Expected 2 active downloads, but got states: $activeStates. All tasks: $allTasks")
        assertEquals(3, activeStates.count { it == DownloadState.Queued })

        // Simulate one failure that should retry
        val activeId = ids.first { manager.getTask(it)?.state == DownloadState.Downloading }
        val activeTaskName = manager.getTask(activeId)!!.name
        
        val failingExecutor = executors[activeTaskName]!!
        val error = DownloadError.NetworkFailure("Simulated failure")
        
        // Emulate what DownloadExecutorImpl does:
        val failedTask = manager.getTask(activeId)!!.copy(
            state = DownloadState.Failed,
            error = error
        )
        failingExecutor.update(failedTask)
        
        // Now emulate the retry scheduling:
        delay(100.milliseconds)
        val retryingTask = failedTask.copy(
            state = DownloadState.Queued,
            scheduledAt = Instant.now().plusMillis(500), // Retry in 500ms
            retryCount = 1
        )
        failingExecutor.update(retryingTask, finish = true)
        
        delay(500.milliseconds)
        
        // At this point, the failed task should have freed a slot, and a new one should have started.
        val newActiveStates = ids.map { manager.getTask(it)?.state }
        assertEquals(2, newActiveStates.count { it == DownloadState.Downloading || it == DownloadState.Starting },
            "After failure, should still have 2 active downloads. States: $newActiveStates")
        
        // The retrying task should be Queued and NOT downloading because it's scheduled in the future
        val retryingTaskFinal = manager.getTask(activeId)!!
        assertEquals(DownloadState.Queued, retryingTaskFinal.state)
        assertTrue(retryingTaskFinal.scheduledAt!!.isAfter(Instant.now()))

        // Wait for retry time
        delay(1000.milliseconds)
        
        // Now the retrying task is eligible, but there are already 2 active ones. 
        // It should STAY queued because the limit is 2.
        val stateAfterRetryTime = manager.getTask(activeId)?.state
        assertEquals(DownloadState.Queued, stateAfterRetryTime)
        
        // Complete one active task
        val otherActiveId = ids.first { id -> id != activeId && manager.getTask(id)?.state == DownloadState.Downloading }
        val otherActiveName = manager.getTask(otherActiveId)!!.name
        executors[otherActiveName]!!.complete()
        
        delay(1000.milliseconds)
        
        // Now the retrying task (or another queued one) should start.
        val finalActiveStates = ids.map { manager.getTask(it)?.state }
        assertEquals(2, finalActiveStates.count { it == DownloadState.Downloading || it == DownloadState.Starting })

        tempDir.toFile().deleteRecursively()
    }
}
