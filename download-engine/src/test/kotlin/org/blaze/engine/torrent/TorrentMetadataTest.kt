package org.blaze.engine.torrent

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorrentMetadataTest {

    private val logger = LoggerFactory.getLogger(TorrentMetadataTest::class.java)

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
    fun `test metadata resolution with big buck bunny torrent`() = runTest {
        val torrentPath = Path.of("/home/kys0adam/Downloads/big-buck-bunny.torrent")
        if (!Files.exists(torrentPath)) {
            logger.warn("Skipping test: Torrent file not found at {}", torrentPath)
            return@runTest
        }

        val tempDir = Files.createTempDirectory("blaze-torrent-metadata-test")
        val request = DownloadRequest.Torrent(
            name = "Initial Name",
            torrentSource = TorrentSource.File(torrentPath),
            destination = tempDir
        )

        val task = DownloadTask(DownloadId.generate(), request.name, request, DownloadState.Queued, null, 0, 0)
        val executor = createExecutor(task, tempDir)
        
        // We wait for the metadata to be resolved. 
        // We'll collect tasks until we see one with totalBytes set or we hit a limit.
        val tasks = mutableListOf<DownloadTask>()
        try {
            executor.execute().take(50).collect { t ->
                tasks.add(t)
                if (t.totalBytes != null && t.totalBytes > 0) {
                    // Metadata resolved!
                    throw RuntimeException("STOP_COLLECTING")
                }
            }
        } catch (e: Exception) {
            if (e.message != "STOP_COLLECTING") throw e
        }

        val resolvedTask = tasks.find { it.totalBytes != null && it.totalBytes > 0 }
        
        assertNotNull(resolvedTask, "Should have resolved metadata. States seen: ${tasks.map { it.state }}")
        
        // The name in the torrent file might be different, but it should definitely not be "Initial Name"
        // if resolution worked.
        assertTrue(resolvedTask.name != "Initial Name", "Name should have been updated from metadata. Got: ${resolvedTask.name}")
        assertTrue(resolvedTask.totalBytes!! > 0, "Total bytes should be positive. Got: ${resolvedTask.totalBytes}")
        assertNotNull(resolvedTask.files, "Files list should not be null")
        assertTrue(resolvedTask.files.isNotEmpty(), "Files list should not be empty")

        resolvedTask.files.forEach { file ->
            logger.info("File: {} ({} bytes)", file.path, file.size)
        }
        
        tempDir.toFile().deleteRecursively()
    }
}
