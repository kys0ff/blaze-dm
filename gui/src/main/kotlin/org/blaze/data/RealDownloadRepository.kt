package org.blaze.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.blaze.domain.models.Download
import org.blaze.domain.repository.DownloadRepository
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.blaze.domain.models.DownloadState as GuiState

class RealDownloadRepository(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope
) : DownloadRepository {

    override val downloads: Flow<List<Download>> = engine.observeAllTasks().map { tasks ->
        tasks.map { it.toGuiDownload() }
    }

    override suspend fun addDownload(url: String, savePath: String, name: String?) {
        val destinationDir = Path.of(savePath)
        withContext(Dispatchers.IO) {
            if (Files.notExists(destinationDir)) {
                Files.createDirectories(destinationDir)
            }
        }

        val request = if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
            val source = if (url.startsWith("magnet:")) {
                TorrentSource.Magnet(url)
            } else {
                TorrentSource.File(Path.of(url))
            }
            DownloadRequest.Torrent(name ?: "Torrent", source, destinationDir.resolve(name ?: "download"))
        } else {
            val decodedUrl = try {
                URLDecoder.decode(url.trim(), StandardCharsets.UTF_8.toString())
            } catch (e: Exception) {
                url.trim()
            }
            
            val fileName = name ?: decodedUrl.substringAfterLast('/')
                .substringBefore('?')
                .replace('+', ' ')
                .takeIf { it.isNotBlank() } ?: "download"
                
            DownloadRequest.Http(fileName, url.trim(), destinationDir.resolve(fileName))
        }
        val id = engine.enqueue(request)
        engine.start(id)
    }

    override suspend fun pauseDownload(id: String) {
        engine.pause(DownloadId(id))
    }

    override suspend fun resumeDownload(id: String) {
        engine.resume(DownloadId(id))
    }

    override suspend fun cancelDownload(id: String) {
        engine.cancel(DownloadId(id))
    }

    override suspend fun removeDownload(id: String, deleteFile: Boolean) {
        engine.remove(DownloadId(id), deleteFile)
    }

    override suspend fun retryDownload(id: String) {
        engine.retry(DownloadId(id))
    }
    
    private fun DownloadTask.toGuiDownload(): Download {
        return Download(
            id = id.value,
            name = name,
            url = when (val r = request) {
                is DownloadRequest.Http -> r.url
                is DownloadRequest.Torrent -> (r.torrentSource as? TorrentSource.Magnet)?.uri ?: "Local Torrent"
            },
            totalSize = totalBytes,
            downloadedSize = downloadedBytes,
            speed = downloadSpeed,
            state = when (state) {
                DownloadState.Queued -> GuiState.QUEUED
                DownloadState.Downloading, DownloadState.Starting, DownloadState.Resuming,
                DownloadState.Verifying, DownloadState.ResolvingMetadata -> GuiState.DOWNLOADING
                DownloadState.Paused, DownloadState.Pausing -> GuiState.PAUSED
                DownloadState.Completed, DownloadState.Seeding -> GuiState.COMPLETED
                DownloadState.Failed -> GuiState.FAILED
                DownloadState.Cancelled -> GuiState.FAILED
                else -> GuiState.QUEUED
            },
            addedAt = createdAt.toEpochMilli(),
            savePath = request.destination.toString()
        )
    }
}
