package org.blaze.engine.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TorrentDownloaderTest {

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

        val downloader = TorrentDownloader(request)
        val tasks = mutableListOf<DownloadTask>()
        downloader.download().collect { 
            tasks.add(it)
        }

        assertTrue(tasks.any { it.state == DownloadState.Failed }, "Should have a failed state. Collected states: ${tasks.map { it.state }}")
        assertTrue(tasks.any { it.error == DownloadError.InvalidTorrent }, "Error should be InvalidTorrent")
        
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test initial state is Starting`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-torrent-test-initial")
        val request = DownloadRequest.Torrent(
            name = "Test Torrent",
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:deadbeef"),
            destination = tempDir
        )

        val downloader = TorrentDownloader(request)
        val tasks = mutableListOf<DownloadTask>()
        
        // We only want the first few states to avoid waiting for timeout
        val job = launch {
            downloader.download().collect { tasks.add(it) }
        }

        delay(100.milliseconds)
        job.cancel()
        
        assertTrue(tasks.isNotEmpty(), "Should have emitted at least one task")
        assertTrue(tasks.any { it.state == DownloadState.Starting }, "First state should be Starting")
        
        tempDir.toFile().deleteRecursively()
    }
}
