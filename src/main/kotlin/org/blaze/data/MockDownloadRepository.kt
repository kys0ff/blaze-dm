package org.blaze.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadRepository
import java.util.UUID

class MockDownloadRepository : DownloadRepository {
    private val _downloads = MutableStateFlow<List<Download>>(
        listOf(
            Download(
                id = "1",
                name = "ubuntu-24.04-desktop-amd64.iso",
                url = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso",
                totalSize = 4_999_999_999L,
                downloadedSize = 2_500_000_000L,
                speed = 10_000_000L,
                state = DownloadState.DOWNLOADING,
                addedAt = System.currentTimeMillis() - 3600000,
                savePath = "/home/user/Downloads"
            ),
            Download(
                id = "2",
                name = "IntelliJIDEA-2024.1.exe",
                url = "https://download.jetbrains.com/idea/ideaIC-2024.1.exe",
                totalSize = 800_000_000L,
                downloadedSize = 800_000_000L,
                speed = 0L,
                state = DownloadState.COMPLETED,
                addedAt = System.currentTimeMillis() - 7200000,
                savePath = "/home/user/Downloads"
            ),
            Download(
                id = "3",
                name = "Blaze-Installer.zip",
                url = "https://github.com/blaze/blaze/releases/download/v1.0.0/Blaze-Installer.zip",
                totalSize = 150_000_000L,
                downloadedSize = 50_000_000L,
                speed = 0L,
                state = DownloadState.PAUSED,
                addedAt = System.currentTimeMillis() - 1800000,
                savePath = "/home/user/Downloads"
            ),
            Download(
                id = "4",
                name = "corrupted-file.bin",
                url = "https://example.com/failed",
                totalSize = 100_000_000L,
                downloadedSize = 10_000_000L,
                speed = 0L,
                state = DownloadState.FAILED,
                addedAt = System.currentTimeMillis() - 600000,
                savePath = "/home/user/Downloads"
            )
        )
    )

    override val downloads: Flow<List<Download>> = _downloads.asStateFlow()

    override suspend fun addDownload(url: String, savePath: String, name: String?) {
        val newDownload = Download(
            id = UUID.randomUUID().toString(),
            name = name ?: url.substringAfterLast('/'),
            url = url,
            totalSize = 0L,
            downloadedSize = 0L,
            speed = 0L,
            state = DownloadState.QUEUED,
            addedAt = System.currentTimeMillis(),
            savePath = savePath
        )
        _downloads.update { it + newDownload }
    }

    override suspend fun pauseDownload(id: String) {
        updateDownloadState(id, DownloadState.PAUSED)
    }

    override suspend fun resumeDownload(id: String) {
        updateDownloadState(id, DownloadState.DOWNLOADING)
    }

    override suspend fun cancelDownload(id: String) {
        updateDownloadState(id, DownloadState.FAILED)
    }

    override suspend fun removeDownload(id: String, deleteFile: Boolean) {
        _downloads.update { it.filterNot { download -> download.id == id } }
    }

    override suspend fun retryDownload(id: String) {
        updateDownloadState(id, DownloadState.QUEUED)
    }

    private fun updateDownloadState(id: String, newState: DownloadState) {
        _downloads.update { list ->
            list.map {
                if (it.id == id) it.copy(state = newState, speed = if (newState == DownloadState.DOWNLOADING) 5_000_000L else 0L)
                else it
            }
        }
    }
}