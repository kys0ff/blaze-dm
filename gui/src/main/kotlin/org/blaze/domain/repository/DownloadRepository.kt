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
        files: List<DownloadFile>? = null
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
}

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
