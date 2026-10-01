package org.blaze.engine.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

import java.nio.file.Path
import java.time.Instant

interface DownloadEngine {
    suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata?

    /**
     * Fetches the `.torrent` metainfo served at [url] over HTTP(S), validates and caches it, then parses
     * its name/size/file list so the caller can preview the torrent and start it from the cached local
     * file rather than re-fetching the URL. [credentials] are ephemeral auth headers for this fetch only.
     * Returns null when the URL serves nothing parsable as a torrent.
     */
    suspend fun fetchTorrentFile(url: String, credentials: Map<String, String> = emptyMap()): TorrentFilePreview?

    /**
     * Read-only probe: is [path] (a moved HTTP partial, or any file inside a moved torrent folder)
     * a Blaze portable download? Returns a safe, display-only summary, or null if it is not.
     */
    suspend fun detectPortableDownload(path: Path): PortableDownloadInfo?

    /**
     * Reconstructs a portable download found at [artifact] into the queue so it resumes from the data
     * already on disk, with no reliance on the originating machine's database or sidecars.
     *
     * - HTTP: [destination] is the folder to resume into; the partial is moved to its canonical
     *   `.part` path and the same-machine resume state is rebuilt from the embedded manifest.
     * - Torrent: the folder holding the `.blaze-portable` marker (resolved from [artifact]) is reused
     *   in place; the embedded metainfo replaces the missing `.torrent`/magnet.
     *
     * Returns the queued [DownloadId] (and schedules it), or null when [artifact] is not portable.
     */
    suspend fun importPortableDownload(artifact: Path, destination: Path): DownloadId?

    suspend fun enqueue(
        request: DownloadRequest,
        totalBytes: Long? = null,
        files: List<DownloadFileMetadata>? = null,
        scheduledAt: Instant? = null,
        priority: DownloadPriority? = null
    ): DownloadId
    suspend fun start(id: DownloadId)
    suspend fun pause(id: DownloadId)
    suspend fun resume(id: DownloadId)
    suspend fun cancel(id: DownloadId)
    suspend fun retry(id: DownloadId)
    suspend fun remove(id: DownloadId, deleteFiles: Boolean = false)

    /**
     * Re-ranks an existing download. Takes effect for a still-queued task immediately (it may be
     * started ahead of lower-priority peers on the next tick); for a running one it only changes the
     * ordering of everything still waiting behind it, without interrupting the transfer.
     */
    suspend fun setPriority(id: DownloadId, priority: DownloadPriority)

    suspend fun getTask(id: DownloadId): DownloadTask?
    fun observeTask(id: DownloadId): Flow<DownloadTask>
    fun observeAllTasks(): StateFlow<List<DownloadTask>>

    suspend fun shutdown()
}
