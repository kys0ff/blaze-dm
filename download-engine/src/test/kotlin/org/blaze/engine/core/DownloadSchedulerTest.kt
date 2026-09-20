package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

        override fun execute(): Flow<DownloadTask> = flow.filterNotNull()
        
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
}
