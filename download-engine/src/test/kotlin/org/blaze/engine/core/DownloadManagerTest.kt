package org.blaze.engine.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            torrentDownloaderFactory = { DummyDownloader() },
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

    private class DummyDownloader : Downloader {
        override fun download(): Flow<DownloadTask> = emptyFlow()
        override suspend fun pause() {}
        override suspend fun cancel() {}
    }
}
