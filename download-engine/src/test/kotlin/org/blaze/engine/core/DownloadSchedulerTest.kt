package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerTest {

    private class TestDownloader(req: DownloadRequest) : Downloader {
        val flow = MutableStateFlow(
            DownloadTask(
                id = DownloadId("dummy"),
                name = req.name,
                request = req,
                state = DownloadState.Downloading,
                totalBytes = 1000,
                downloadedBytes = 0,
                downloadSpeed = 100
            )
        )
        override fun download(): Flow<DownloadTask> = flow
        override suspend fun pause() {}
        override suspend fun cancel() {}
        
        fun complete() {
            flow.value = flow.value.copy(state = DownloadState.Completed)
        }
    }

    @Test
    fun `test scheduler concurrency and runtime updates`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-scheduler-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        // Set max concurrent downloads to 1 initially
        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 1) }

        val downloaders = mutableListOf<TestDownloader>()

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            httpDownloaderFactory = { req ->
                val dl = TestDownloader(req)
                downloaders.add(dl)
                dl
            },
            torrentDownloaderFactory = { req ->
                val dl = TestDownloader(req)
                downloaders.add(dl)
                dl
            },
            settingsRepository = settingsRepo
        )

        // Enqueue 3 downloads
        val id1 = manager.enqueue(DownloadRequest.Http("File1", "http://url1", Path.of(".")))
        val id2 = manager.enqueue(DownloadRequest.Http("File2", "http://url2", Path.of(".")))
        val id3 = manager.enqueue(DownloadRequest.Http("File3", "http://url3", Path.of(".")))

        // Start all
        manager.start(id1)
        manager.start(id2)
        manager.start(id3)

        // Wait a small moment for coroutines to process the queue
        advanceTimeBy(100.milliseconds)

        // Only 1 should be active, others queued
        assertEquals(DownloadState.Downloading, manager.getTask(id1)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(id2)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(id3)?.state)

        // Complete the first download
        downloaders.firstOrNull { it.flow.value.name == "File1" }?.complete()
        advanceTimeBy(100.milliseconds)

        // Now File2 should be started automatically
        assertEquals(DownloadState.Completed, manager.getTask(id1)?.state)
        assertEquals(DownloadState.Downloading, manager.getTask(id2)?.state)
        assertEquals(DownloadState.Queued, manager.getTask(id3)?.state)

        // Dynamically increase max concurrency to 2
        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 2) }
        advanceTimeBy(100.milliseconds)

        // File3 should now start as well because a slot opened up
        assertEquals(DownloadState.Downloading, manager.getTask(id3)?.state)

        tempDir.toFile().deleteRecursively()
    }
}
