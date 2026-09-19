package org.blaze.engine.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DownloadEngine {
    suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata?
    suspend fun enqueue(request: DownloadRequest): DownloadId
    suspend fun start(id: DownloadId)
    suspend fun pause(id: DownloadId)
    suspend fun resume(id: DownloadId)
    suspend fun cancel(id: DownloadId)
    suspend fun retry(id: DownloadId)
    suspend fun remove(id: DownloadId, deleteFiles: Boolean = false)

    suspend fun getTask(id: DownloadId): DownloadTask?
    fun observeTask(id: DownloadId): Flow<DownloadTask>
    fun observeAllTasks(): StateFlow<List<DownloadTask>>

    suspend fun shutdown()
}
