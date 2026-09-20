package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @Test
    fun `test remove torrent download does not delete the whole parent folder`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-dm-test")
        val storageDir = tempDir.resolve("storage")
        val downloadsDir = tempDir.resolve("downloads")
        Files.createDirectories(storageDir)
        Files.createDirectories(downloadsDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            httpDownloaderFactory = { DummyDownloader() },
            torrentDownloaderFactory = { _, _ -> DummyDownloader() },
            settingsRepository = settingsRepo
        )

        // Scenario: destination is the shared downloads folder itself or a shared subfolder,
        // and torrent name is "my_torrent".
        val torrentName = "my_torrent"
        val request = DownloadRequest.Torrent(
            name = torrentName,
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:123"),
            destination = downloadsDir
        )

        val id = manager.enqueue(request)

        // Create the torrent's downloaded files/folders inside downloadsDir
        val torrentFolder = downloadsDir.resolve(torrentName)
        Files.createDirectories(torrentFolder)
        val torrentFile = torrentFolder.resolve("data.txt")
        Files.writeString(torrentFile, "torrent data")

        // Create an unrelated file in the parent folder to make sure it doesn't get removed
        val unrelatedFile = downloadsDir.resolve("important_user_file.txt")
        Files.writeString(unrelatedFile, "do not delete me")

        // Verify they exist before remove
        assertTrue(Files.exists(torrentFolder))
        assertTrue(Files.exists(torrentFile))
        assertTrue(Files.exists(unrelatedFile))

        // Act
        manager.remove(id, deleteFiles = true)

        // Assert: torrent folder and files should be deleted, but unrelated file MUST remain
        assertFalse(Files.exists(torrentFile), "Torrent file should be deleted")
        assertFalse(Files.exists(torrentFolder), "Torrent folder should be deleted")
        assertTrue(Files.exists(unrelatedFile), "Unrelated file in parent folder must not be deleted!")

        // Clean up
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test progress is preserved when manager is restarted`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-restart-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        
        val id = DownloadId.generate()
        val initialDownloaded = 500L
        val initialTotal = 1000L
        
        // 1. Manually save a record to the repository to simulate a previous run
        repository.saveAll(listOf(
            DownloadRecord(
                id = id.value,
                name = "Test",
                type = "HTTP",
                url = "http://test",
                destination = ".",
                state = "DOWNLOADING",
                totalBytes = initialTotal,
                downloadedBytes = initialDownloaded,
                addedAt = System.currentTimeMillis()
            )
        ))

        // 2. Simulate restart: create a manager instance
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            httpDownloaderFactory = { DummyDownloader() },
            torrentDownloaderFactory = { _, _ -> DummyDownloader() },
            settingsRepository = settingsRepo
        )

        // Wait for loadTasks to complete.
        // We use virtual time via delay, but loadTasks uses Dispatchers.IO.
        var task: DownloadTask? = null
        withTimeout(5000.milliseconds) {
            while (true) {
                task = manager.getTask(id)
                if (task != null) break
                Thread.sleep(10)
                delay(50.milliseconds)
            }
        }
        
        assertTrue(task != null, "Task should be loaded")
        assertEquals(initialDownloaded, task.downloadedBytes, "Downloaded bytes should be preserved")
        assertEquals(initialTotal, task.totalBytes, "Total bytes should be preserved")

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test torrent metadata cleanup after success`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-cleanup-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val stateFlow = MutableStateFlow<DownloadTask?>(null)
        val downloader = object : Downloader {
            override fun download(): Flow<DownloadTask> = stateFlow.filterNotNull()
            override suspend fun pause() {}
            override suspend fun cancel() {}
        }

        var metadataCallback: ((ByteArray) -> Unit)? = null
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            httpDownloaderFactory = { DummyDownloader() },
            torrentDownloaderFactory = { _, onMetadata ->
                metadataCallback = onMetadata
                downloader
            },
            settingsRepository = settingsRepo
        )

        val request = DownloadRequest.Torrent("CleanupTest", TorrentSource.Magnet("magnet:?xt=urn:btih:123"), Path.of("."))
        val id = manager.enqueue(request)
        manager.start(id)

        // Wait for actuallyStart to be called and downloader to be created
        withTimeout(5000.milliseconds) {
            while (metadataCallback == null) delay(50.milliseconds)
        }

        // 1. Simulate metadata resolution
        val task = DownloadTask(id, "CleanupTest", request, DownloadState.Downloading, 1000L, 0L, 0L)
        stateFlow.value = task

        // Trigger callback manually to simulate TorrentDownloader behavior
        metadataCallback?.invoke("dummy torrent content".toByteArray())

        // Wait for cache to be written. We use real time for the wait because the IO happens on a real thread.
        val cacheFile = storageDir.resolve("cache").resolve("${id.value}.torrent")
        
        // Use a loop with real sleep to wait for the IO thread if needed, 
        // or just let runTest handle it if we are lucky.
        // Actually, let's try to increase virtual time and see if it helps, 
        // but virtual time won't help if IO thread is stuck.
        
        var found = false
        for (i in 1..100) {
            if (Files.exists(cacheFile)) {
                found = true
                break
            }
            delay(50.milliseconds)
        }
        assertTrue(found, "Cache file should be created")

        // 2. Simulate completion
        stateFlow.value = task.copy(state = DownloadState.Completed)

        // Wait for cleanup
        var deleted = false
        for (i in 1..100) {
            if (!Files.exists(cacheFile)) {
                deleted = true
                break
            }
            delay(50.milliseconds)
        }
        assertTrue(deleted, "Cache file should be cleaned up after completion")

        tempDir.toFile().deleteRecursively()
    }

    private class DummyDownloader : Downloader {
        override fun download(): Flow<DownloadTask> = emptyFlow()
        override suspend fun pause() {}
        override suspend fun cancel() {}
    }
}
