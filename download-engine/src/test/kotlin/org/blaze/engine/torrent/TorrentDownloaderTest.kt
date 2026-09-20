package org.blaze.engine.torrent

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.execution.DownloadExecutorImpl
import org.blaze.engine.retry.DefaultRetryPolicy
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TorrentDownloaderTest {

    private fun createExecutor(task: DownloadTask, tempDir: Path): DownloadExecutorImpl {
        val storage = DefaultFileStorage()
        val settingsRepo = EngineSettingsRepository(tempDir)
        return DownloadExecutorImpl(
            initialTask = task,
            httpClient = HttpClient(),
            storage = storage,
            settingsRepository = settingsRepo,
            retryPolicy = DefaultRetryPolicy(DownloadSettings()),
            onMetadataResolved = { _, _ -> }
        )
    }

    @Test
    fun `test invalid torrent file should emit failure`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-torrent-test-invalid")
        val invalidTorrent = tempDir.resolve("invalid.torrent")
        Files.writeString(invalidTorrent, "not a bencoded torrent")

        val request = DownloadRequest.Torrent(
            name = "Invalid Torrent",
            torrentSource = TorrentSource.File(invalidTorrent),
            destination = tempDir
        )

        val task = DownloadTask(DownloadId.generate(), request.name, request, DownloadState.Queued, null, 0, 0)
        val executor = createExecutor(task, tempDir)
        val tasks = mutableListOf<DownloadTask>()
        
        executor.execute().collect { 
            tasks.add(it)
        }

        assertTrue(tasks.any { it.state == DownloadState.Failed }, "Should have a failed state. Collected states: ${tasks.map { it.state }}")
        assertTrue(tasks.any { it.error == DownloadError.InvalidTorrent }, "Error should be InvalidTorrent")
        
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test initial state is Starting`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("blaze-torrent-test-initial")
            val request = DownloadRequest.Torrent(
                name = "Test Torrent",
                torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd"),
                destination = tempDir
            )

            val task = DownloadTask(DownloadId.generate(), request.name, request, DownloadState.Starting, null, 0, 0)
            val executor = createExecutor(task, tempDir)
            val tasks = mutableListOf<DownloadTask>()
            
            val job = launch {
                executor.execute().collect { tasks.add(it) }
            }

            var attempts = 0
            while (tasks.isEmpty() && attempts < 50) {
                delay(20.milliseconds)
                attempts++
            }
            job.cancel()
            
            assertTrue(tasks.isNotEmpty(), "Should have emitted at least one task")
            assertTrue(tasks.any { it.state == DownloadState.Starting }, "First state should be Starting")
            
            tempDir.toFile().deleteRecursively()
        }
    }
}
