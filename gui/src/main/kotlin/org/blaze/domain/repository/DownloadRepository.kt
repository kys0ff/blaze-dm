package org.blaze.domain.repository

import kotlinx.coroutines.flow.Flow
import org.blaze.domain.models.Download

interface DownloadRepository {
    val downloads: Flow<List<Download>>

    suspend fun fetchMetadata(url: String): DownloadMetadata?

    suspend fun addDownload(
        url: String,
        savePath: String,
        name: String? = null,
        fileIndices: List<Int>? = null,
        totalSize: Long? = null,
        files: List<DownloadFile>? = null,
        scheduledAt: Long? = null
    )
    suspend fun getDestinationPath(url: String, savePath: String, name: String? = null): String
    suspend fun pauseDownload(id: String)
    suspend fun resumeDownload(id: String)
    suspend fun cancelDownload(id: String)
    suspend fun removeDownload(id: String, deleteFile: Boolean = false)
    suspend fun retryDownload(id: String)
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun clearCompleted()

    /**
     * Reads a moved artifact at [artifactPath] and returns a display-only summary when it is a Blaze
     * portable download, or null otherwise. Detection is by embedded magic, never by file name.
     */
    suspend fun detectPortable(artifactPath: String): PortableInfo?

    /**
     * Imports a detected portable artifact into [destinationDir], reconstructing local state so the
     * download resumes. Returns true when a resumable download was queued for the artifact.
     */
    suspend fun importPortable(artifactPath: String, destinationDir: String): Boolean
}

/** Protocol family a detected portable artifact carries. */
enum class PortableKind { HTTP, TORRENT }

/**
 * Domain view of a discovered portable download. Deliberately free of engine types and of any
 * credential material — [source] is a display label only (URL or torrent name).
 */
data class PortableInfo(
    val kind: PortableKind,
    val suggestedName: String,
    val source: String?,
    val totalBytes: Long,
    val availableBytes: Long,
    val resumable: Boolean,
    val multiFile: Boolean,
    val mayRequireCredentials: Boolean
)

data class DownloadMetadata(
    val name: String,
    val totalSize: Long?,
    val files: List<DownloadFile>? = null
)

data class DownloadFile(
    val name: String,
    val size: Long,
    val index: Int
)
