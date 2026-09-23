package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadPriority
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.scheduler.DownloadScheduler
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerTest {

    private class MockExecutor(val task: DownloadTask) : DownloadExecutor {
        val flow = MutableStateFlow<DownloadTask?>(null)
        
        init {
             flow.value = DownloadTask(
                id = task.id,
                name = task.name,
                request = task.request,
                state = DownloadState.Downloading,
                totalBytes = 1000,
                downloadedBytes = 0,
                downloadSpeed = 100
            )
        }

        override fun execute(): Flow<DownloadTask> = channelFlow {
            flow.collect { currentTask ->
                if (currentTask != null) {
                    send(currentTask)
                    if (currentTask.state == DownloadState.Completed) {
                        close()
                    }
                }
            }
        }
        
        fun complete() {
            flow.value = flow.value?.copy(state = DownloadState.Completed)
        }
    }

    @Test
    fun `test scheduler concurrency and runtime updates`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-scheduler-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 1) }

        val executors = mutableListOf<MockExecutor>()

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = { task ->
                val exec = MockExecutor(task)
                executors.add(exec)
                exec
            }
        )

        val id1 = manager.enqueue(DownloadRequest.Http("File1", "http://url1", Path.of(".")))
        val id2 = manager.enqueue(DownloadRequest.Http("File2", "http://url2", Path.of(".")))
        val id3 = manager.enqueue(DownloadRequest.Http("File3", "http://url3", Path.of(".")))

        manager.start(id1)
        manager.start(id2)
        manager.start(id3)

        // Give some time for coroutines
        delay(200.milliseconds)

        assertEquals(DownloadState.Downloading, manager.getTask(id1)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(id2)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(id3)?.state)

        executors.firstOrNull { it.task.request.name == "File1" }?.complete()
        delay(200.milliseconds)

        assertEquals(DownloadState.Completed, manager.getTask(id1)?.state)
        assertEquals(DownloadState.Downloading, manager.getTask(id2)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(id3)?.state)

        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 2) }
        delay(200.milliseconds)

        assertEquals(DownloadState.Downloading, manager.getTask(id3)?.state)

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `restarting a finished download zeroes its stale progress but a paused one keeps it`() = runTest {
        val storageDir = Files.createTempDirectory("blaze-start-reset").resolve("storage")
        Files.createDirectories(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 2) }

        // An executor that never reports, so the Starting snapshot the reset writes stays observable.
        val hangingFactory: (DownloadTask) -> DownloadExecutor = {
            object : DownloadExecutor {
                override fun execute(): Flow<DownloadTask> = flow { awaitCancellation() }
            }
        }
        val updates = mutableListOf<DownloadTask>()
        val scheduler = DownloadScheduler(backgroundScope, settingsRepo, hangingFactory) { updates.add(it) }

        val finished = DownloadTask(
            id = DownloadId.generate(), name = "done",
            request = DownloadRequest.Http("done", "http://u", Path.of(".")),
            state = DownloadState.Completed, totalBytes = 1000, downloadedBytes = 1000,
            downloadSpeed = 0, progress = 1f, completedAt = Instant.now()
        )
        scheduler.enqueue(finished)
        scheduler.start(finished.id)

        val restarted = updates.last { it.id == finished.id }
        assertEquals(0L, restarted.downloadedBytes, "a re-download must not carry the old 100% count")
        assertNull(restarted.completedAt)

        // A paused download still has bytes on disk worth resuming, so its count survives a start.
        val paused = DownloadTask(
            id = DownloadId.generate(), name = "partial",
            request = DownloadRequest.Http("partial", "http://u", Path.of(".")),
            state = DownloadState.Paused, totalBytes = 1000, downloadedBytes = 500, downloadSpeed = 0
        )
        scheduler.enqueue(paused)
        scheduler.start(paused.id)

        assertEquals(500L, updates.last { it.id == paused.id }.downloadedBytes)
    }

    @Test
    fun `when a slot frees the higher-priority download overtakes an older queued one`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-priority")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 1) }

        val executors = mutableListOf<MockExecutor>()
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = { task -> MockExecutor(task).also { executors.add(it) } }
        )

        // Occupy the single slot first.
        val running = manager.enqueue(DownloadRequest.Http("Running", "http://r", Path.of(".")))
        manager.start(running)
        delay(200.milliseconds)
        assertEquals(DownloadState.Downloading, manager.getTask(running)?.state)

        // An older LOW, then a newer HIGH - recency alone would favour LOW, priority must win.
        val low = manager.enqueue(
            DownloadRequest.Http("Low", "http://l", Path.of(".")),
            priority = DownloadPriority.LOW,
        )
        delay(20.milliseconds)
        val high = manager.enqueue(
            DownloadRequest.Http("High", "http://h", Path.of(".")),
            priority = DownloadPriority.HIGH,
        )
        delay(200.milliseconds)
        assertEquals(DownloadState.Queued, manager.getTask(low)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(high)?.state)

        executors.first { it.task.request.name == "Running" }.complete()
        delay(300.milliseconds)

        assertEquals(DownloadState.Downloading, manager.getTask(high)?.state, "priority beats creation order")
        assertEquals(DownloadState.Queued, manager.getTask(low)?.state)

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `setPriority on a running download survives the next executor emission`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-setpriority-midflight")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val executors = mutableListOf<MockExecutor>()
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = { task -> MockExecutor(task).also { executors.add(it) } }
        )

        val id = manager.enqueue(DownloadRequest.Http("live", "http://u", Path.of(".")))
        manager.start(id)
        delay(200.milliseconds)
        assertEquals(DownloadState.Downloading, manager.getTask(id)?.state)
        assertEquals(DownloadPriority.NORMAL, manager.getTask(id)?.priority, "starts at the default")

        // The user raises the priority mid-flight, through the same verb the row's context menu hits.
        manager.setPriority(id, DownloadPriority.HIGH)
        assertEquals(DownloadPriority.HIGH, manager.getTask(id)?.priority)

        // The next progress emission comes from the executor's launch-time snapshot, whose priority
        // is still NORMAL. Without the scheduler-side merge this reverts the user's change (the
        // badge appeared for a moment then disappeared again).
        val exec = executors.first { it.task.request.name == "live" }
        exec.flow.value = exec.flow.value?.copy(downloadedBytes = 500)
        delay(200.milliseconds)

        val after = manager.getTask(id)
        assertEquals(500L, after?.downloadedBytes, "the executor's own progress is preserved")
        assertEquals(
            DownloadPriority.HIGH,
            after?.priority,
            "priority set mid-flight must not be reverted by a later executor emission",
        )

        tempDir.toFile().deleteRecursively()
    }
}
