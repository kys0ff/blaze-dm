package org.blaze.engine.torrent

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorrentMetadataTest {

    private val logger = LoggerFactory.getLogger(TorrentMetadataTest::class.java)

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

        val downloader = TorrentDownloader(request)
        
        // We wait for the metadata to be resolved. 
        // We'll collect tasks until we see one with totalBytes set or we hit a limit.
        val tasks = mutableListOf<DownloadTask>()
        try {
            downloader.download().take(50).collect { task ->
                tasks.add(task)
                if (task.totalBytes != null && task.totalBytes > 0) {
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
        
        logger.info("Resolved Torrent Name: {}", resolvedTask.name)
        logger.info("Resolved Torrent Size: {}", resolvedTask.totalBytes)
        
        tempDir.toFile().deleteRecursively()
    }
}
