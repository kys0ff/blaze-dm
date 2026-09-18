package org.blaze.domain.repository

import kotlinx.coroutines.flow.Flow
import org.blaze.domain.models.Download

interface DownloadRepository {
    val downloads: Flow<List<Download>>

    suspend fun addDownload(url: String, savePath: String, name: String? = null)
    suspend fun pauseDownload(id: String)
    suspend fun resumeDownload(id: String)
    suspend fun cancelDownload(id: String)
    suspend fun removeDownload(id: String, deleteFile: Boolean = false)
    suspend fun retryDownload(id: String)
}