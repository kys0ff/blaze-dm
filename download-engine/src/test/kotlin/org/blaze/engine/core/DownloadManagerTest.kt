package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.execution.DownloadExecutor
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

    private class MockExecutor : DownloadExecutor {
        val flow = MutableStateFlow<DownloadTask?>(null)
        override fun execute(): Flow<DownloadTask> = flow.filterNotNull()
        
        fun emit(updatedTask: DownloadTask) {
            flow.value = updatedTask
        }
    }

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
            settingsRepository = settingsRepo
        )

        val torrentName = "my_torrent"
        val request = DownloadRequest.Torrent(
            name = torrentName,
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:123"),
            destination = downloadsDir
        )

        val id = manager.enqueue(request)

        val torrentFolder = downloadsDir.resolve(torrentName)
        Files.createDirectories(torrentFolder)
        val torrentFile = torrentFolder.resolve("data.txt")
        Files.writeString(torrentFile, "torrent data")

        val unrelatedFile = downloadsDir.resolve("important_user_file.txt")
        Files.writeString(unrelatedFile, "do not delete me")

        assertTrue(Files.exists(torrentFolder))
        assertTrue(Files.exists(torrentFile))
        assertTrue(Files.exists(unrelatedFile))

        manager.remove(id, deleteFiles = true)

        // Wait a bit for the async deletion
        delay(100.milliseconds)

        assertFalse(Files.exists(torrentFile), "Torrent file should be deleted")
        assertFalse(Files.exists(torrentFolder), "Torrent folder should be deleted")
        assertTrue(Files.exists(unrelatedFile), "Unrelated file in parent folder must not be deleted!")

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

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo
        )

        var task: DownloadTask? = null
        withTimeout(5000.milliseconds) {
            while (true) {
                task = manager.getTask(id)
                if (task != null) break
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

        var mockExecutor: MockExecutor? = null
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = {
                val mock = MockExecutor()
                mockExecutor = mock
                mock
            }
        )

        val request = DownloadRequest.Torrent("CleanupTest", TorrentSource.Magnet("magnet:?xt=urn:btih:123"), Path.of("."))
        val id = manager.enqueue(request)
        manager.start(id)

        withTimeout(5000.milliseconds) {
            while (mockExecutor == null) delay(50.milliseconds)
        }

        val task = DownloadTask(id, "CleanupTest", request, DownloadState.Downloading, 1000L, 0L, 0L)
        mockExecutor?.emit(task)

        // Metadata resolution (in DownloadExecutorImpl it calls onMetadataResolved, here we mock it by manually updating tasks in manager)
        // Actually the original test was testing that DownloadManager calls cleanupMetadata when downloader emits Completed.
        // My DownloadManager handles metadata caching via onMetadataResolved callback in executor.
        
        // Let's simulate metadata caching
        val cacheFile = storageDir.resolve("cache").resolve("${id.value}.torrent")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(cacheFile, "dummy content")
        assertTrue(Files.exists(cacheFile))

        // Simulate completion
        mockExecutor?.emit(task.copy(state = DownloadState.Completed))

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
}
