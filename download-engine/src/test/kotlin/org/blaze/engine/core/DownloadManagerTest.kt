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
import kotlin.test.assertNull
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
        // The destination of a torrent request is the download's own folder, which is how the
        // service builds it (`chosen dir / name`) - not the directory it sits in.
        val torrentFolder = downloadsDir.resolve(torrentName)
        val request = DownloadRequest.Torrent(
            name = torrentName,
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:123"),
            destination = torrentFolder
        )

        val id = manager.enqueue(request)

        try {
            Files.createDirectories(torrentFolder.resolve("subs"))
            val torrentFile = torrentFolder.resolve("data.txt")
            Files.writeString(torrentFile, "torrent data")
            val nestedFile = torrentFolder.resolve("subs/en.srt")
            Files.writeString(nestedFile, "subtitles")

            val unrelatedFile = downloadsDir.resolve("important_user_file.txt")
            Files.writeString(unrelatedFile, "do not delete me")

            assertTrue(Files.exists(torrentFolder))
            assertTrue(Files.exists(torrentFile))
            assertTrue(Files.exists(unrelatedFile))

            manager.remove(id, deleteFiles = true)

            // No waiting: removing files is awaited, so the answer is final the moment it returns.
            assertFalse(Files.exists(torrentFolder), "Torrent folder should be deleted")
            assertFalse(Files.exists(torrentFile), "Torrent file should be deleted")
            assertFalse(Files.exists(nestedFile), "A file in a torrent subfolder should be deleted")
            assertTrue(
                Files.exists(unrelatedFile),
                "Unrelated file in parent folder must not be deleted!"
            )
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test remove http download with no files on disk is a clean no-op`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-dm-nofiles-test")
        val storageDir = tempDir.resolve("storage")
        val downloadsDir = tempDir.resolve("downloads")
        Files.createDirectories(storageDir)
        Files.createDirectories(downloadsDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
        )

        // A download that never wrote bytes: none of the finished / partial / resume files exist.
        val target = downloadsDir.resolve("nothing.bin")
        val id = manager.enqueue(
            DownloadRequest.Http(name = "nothing.bin", url = "http://test", destination = target)
        )

        try {
            // Removing it with deleteFiles must not raise (and must not read as a failed delete):
            // there is simply nothing on disk to take away.
            manager.remove(id, deleteFiles = true)

            assertFalse(Files.exists(target))
            assertNull(manager.getTask(id), "The download should be gone from the manager")
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test remove http download deletes only its own artifacts and leaves siblings`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-dm-http-test")
        val storageDir = tempDir.resolve("storage")
        val downloadsDir = tempDir.resolve("downloads")
        Files.createDirectories(storageDir)
        Files.createDirectories(downloadsDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
        )

        val target = downloadsDir.resolve("movie.mp4")
        val partial = downloadsDir.resolve("movie.mp4.part")
        val resume = downloadsDir.resolve("movie.mp4.meta")
        val unrelated = downloadsDir.resolve("keepme.txt")
        val id = manager.enqueue(
            DownloadRequest.Http(name = "movie.mp4", url = "http://test", destination = target)
        )

        try {
            Files.writeString(target, "finished")
            Files.writeString(partial, "partial")
            Files.writeString(resume, "resume state")
            Files.writeString(unrelated, "do not delete me")

            manager.remove(id, deleteFiles = true)

            // Awaited deletion: the download's three artifacts are gone the moment remove returns.
            assertFalse(Files.exists(target), "The finished file should be deleted")
            assertFalse(Files.exists(partial), "The partial file should be deleted")
            assertFalse(Files.exists(resume), "The resume sidecar should be deleted")
            assertTrue(Files.exists(unrelated), "An unrelated file in the folder must survive")
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
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

        repository.saveAll(
            listOf(
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
            )
        )

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo
        )

        try {
            var task: DownloadTask? = null
            (1..500).forEach { _ ->
                task = manager.getTask(id)
                if (task != null) return@forEach
                Thread.sleep(10)
            }

            assertTrue(task != null, "Task should be loaded")
            assertEquals(
                initialDownloaded,
                task.downloadedBytes,
                "Downloaded bytes should be preserved"
            )
            assertEquals(initialTotal, task.totalBytes, "Total bytes should be preserved")
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
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

        val request = DownloadRequest.Torrent(
            "CleanupTest",
            TorrentSource.Magnet("magnet:?xt=urn:btih:123"),
            Path.of(".")
        )
        val id = manager.enqueue(request)
        manager.start(id)

        withTimeout(5000.milliseconds) {
            while (mockExecutor == null) delay(50.milliseconds)
        }

        val task =
            DownloadTask(id, "CleanupTest", request, DownloadState.Downloading, 1000L, 0L, 0L)
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

        try {
            var deleted = false
            (1..100).forEach { _ ->
                if (!Files.exists(cacheFile)) {
                    deleted = true
                    return@forEach
                }
                delay(50.milliseconds)
            }
            assertTrue(deleted, "Cache file should be cleaned up after completion")
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test file indices are preserved in record`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-indices-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val id = DownloadId.generate()
        val indices = listOf(1, 3, 5)

        repository.saveAll(
            listOf(
                DownloadRecord(
                    id = id.value,
                    name = "TorrentTest",
                    type = "TORRENT",
                    magnetUri = "magnet:?xt=urn:btih:123",
                    destination = ".",
                    state = "QUEUED",
                    addedAt = System.currentTimeMillis(),
                    fileIndices = indices
                )
            )
        )

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo
        )

        try {
            var task: DownloadTask? = null
            (1..500).forEach { _ ->
                task = manager.getTask(id)
                if (task != null) return@forEach
                Thread.sleep(10)
            }

            requireNotNull(task) { "Task not loaded within timeout" }

            val request = task.request as DownloadRequest.Torrent
            assertEquals(
                indices,
                request.fileIndices,
                "File indices should be preserved in request"
            )
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test retryCount resets on manual start`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-retry-reset-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val id = DownloadId.generate()
        repository.saveAll(
            listOf(
                DownloadRecord(
                    id = id.value,
                    name = "RetryResetTest",
                    type = "HTTP",
                    url = "http://test",
                    destination = ".",
                    state = "FAILED",
                    addedAt = System.currentTimeMillis(),
                    retryCount = 3
                )
            )
        )

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = { MockExecutor() }
        )

        try {
            var task: DownloadTask? = null
            (1..500).forEach { _ ->
                task = manager.getTask(id)
                if (task != null) return@forEach
                Thread.sleep(10)
            }
            manager.start(id)
            (1..500).forEach { _ ->
                task = manager.getTask(id)
                if (task != null && task.retryCount == 0) return@forEach
                Thread.sleep(10)
            }
            assertEquals(0, task?.retryCount, "retryCount should reset to 0 after manual start")
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test paused and failed downloads resume on startup`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-startup-resume-test")
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)

        val repository = DownloadRepository(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)

        val idPaused = DownloadId.generate()
        val idFailed = DownloadId.generate()
        repository.saveAll(
            listOf(
                DownloadRecord(
                    id = idPaused.value,
                    name = "PausedTest",
                    type = "HTTP",
                    url = "http://test1",
                    destination = ".",
                    state = "PAUSED",
                    addedAt = System.currentTimeMillis()
                ),
                DownloadRecord(
                    id = idFailed.value,
                    name = "FailedTest",
                    type = "HTTP",
                    url = "http://test2",
                    destination = ".",
                    state = "FAILED",
                    addedAt = System.currentTimeMillis()
                )
            )
        )

        settingsRepo.updateSettings { it.copy(resumeDownloadsOnStartup = true) }

        val manager = DownloadManager(
            scope = backgroundScope,
            repository = repository,
            settingsRepository = settingsRepo,
            executorFactory = { MockExecutor() }
        )

        try {
            var taskPaused: DownloadTask? = null
            var taskFailed: DownloadTask? = null
            (1..500).forEach { _ ->
                taskPaused = manager.getTask(idPaused)
                taskFailed = manager.getTask(idFailed)
                if (taskPaused != null && taskFailed != null) return@forEach
                Thread.sleep(10)
            }
            assertTrue(
                taskPaused?.state == DownloadState.Queued || taskPaused?.state == DownloadState.Starting,
                "Paused downloads should transition to Queued or Starting when resumeDownloadsOnStartup is true"
            )
            assertTrue(
                taskFailed?.state == DownloadState.Queued || taskFailed?.state == DownloadState.Starting,
                "Failed downloads should transition to Queued or Starting when resumeDownloadsOnStartup is true"
            )
        } finally {
            manager.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }
}
