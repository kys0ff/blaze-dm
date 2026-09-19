package org.blaze.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import org.blaze.domain.models.Download
import org.blaze.domain.repository.DownloadMetadata
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

class DownloadRepositoryImpl(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope
) : DownloadRepository {

    override val downloads: Flow<List<Download>> =
        engine.observeAllTasks()
            .map { tasks -> tasks.map { it.toGuiDownload() } }
            .shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
                replay = 1
            )

    override suspend fun fetchMetadata(url: String): DownloadMetadata? {
        val request = createRequest(url, Path.of(System.getProperty("java.io.tmpdir")), null)
        return engine.fetchMetadata(request)?.let {
            DownloadMetadata(it.name, it.totalSize)
        }
    }

    private suspend fun createRequest(url: String, destinationDir: Path, name: String?): DownloadRequest =
        if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
            val source = if (url.startsWith("magnet:")) {
                TorrentSource.Magnet(url)
            } else {
                TorrentSource.File(Path.of(url))
            }

            val torrentName = name?.takeIf { it.isNotBlank() } ?: "Torrent"
            val folderName = name?.takeIf { it.isNotBlank() } ?: "download"

            DownloadRequest.Torrent(
                torrentName,
                source,
                destinationDir.resolve(folderName)
            )
        } else {
            val trimmedUrl = url.trim()

            val decodedUrl = try {
                withContext(Dispatchers.IO) {
                    URLDecoder.decode(
                        trimmedUrl,
                        StandardCharsets.UTF_8.toString()
                    )
                }
            } catch (_: IllegalArgumentException) {
                trimmedUrl
            }

            val fileName = name
                ?: decodedUrl
                    .substringAfterLast('/')
                    .substringBefore('?')
                    .replace('+', ' ')
                    .takeIf { it.isNotBlank() }
                ?: "download"

            DownloadRequest.Http(
                fileName,
                trimmedUrl,
                destinationDir.resolve(fileName)
            )
        }

    override suspend fun addDownload(url: String, savePath: String, name: String?) {
        val destinationDir = Path.of(savePath)

        withContext(Dispatchers.IO) {
            if (Files.notExists(destinationDir)) {
                Files.createDirectories(destinationDir)
            }
        }

        val request = createRequest(url, destinationDir, name)

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

    override suspend fun pauseAll() {
        engine.observeAllTasks().value.forEach { task ->
            if (task.state is DownloadState.Downloading ||
                task.state is DownloadState.Queued ||
                task.state is DownloadState.Starting ||
                task.state is DownloadState.Resuming ||
                task.state is DownloadState.Verifying ||
                task.state is DownloadState.ResolvingMetadata
            ) {
                engine.pause(task.id)
            }
        }
    }

    override suspend fun resumeAll() {
        engine.observeAllTasks().value.forEach { task ->
            if (task.state is DownloadState.Paused || task.state is DownloadState.Pausing) {
                engine.resume(task.id)
            }
        }
    }

    override suspend fun clearCompleted() {
        engine.observeAllTasks().value.forEach { task ->
            if (task.state is DownloadState.Completed || task.state is DownloadState.Seeding) {
                engine.remove(task.id, deleteFiles = false)
            }
        }
    }

    private fun DownloadTask.toGuiDownload(): Download {
        return Download(
            id = id.value,
            name = name,
            url = when (val r = request) {
                is DownloadRequest.Http -> r.url
                is DownloadRequest.Torrent ->
                    (r.torrentSource as? TorrentSource.Magnet)?.uri ?: "Local Torrent"
            },
            totalSize = totalBytes,
            downloadedSize = downloadedBytes,
            speed = downloadSpeed,
            peers = peers,
            state = when (state) {
                DownloadState.Queued -> GuiState.QUEUED

                DownloadState.Downloading,
                DownloadState.Starting,
                DownloadState.Resuming,
                DownloadState.Verifying,
                DownloadState.ResolvingMetadata -> GuiState.DOWNLOADING

                DownloadState.Paused,
                DownloadState.Pausing -> GuiState.PAUSED

                DownloadState.Completed,
                DownloadState.Seeding -> GuiState.COMPLETED

                DownloadState.Failed -> GuiState.FAILED
                DownloadState.Cancelled -> GuiState.FAILED
            },
            addedAt = createdAt.toEpochMilli(),
            savePath = request.destination.toString(),
            error = error
        )
    }
}
