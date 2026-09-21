package org.blaze.engine.api

import java.time.Instant
import kotlin.time.Duration

data class DownloadTask(
    val id: DownloadId,
    val name: String,
    val request: DownloadRequest,
    val state: DownloadState,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val downloadSpeed: Long, // bytes per second
    val uploadSpeed: Long = 0,
    val peers: Int = 0,
    val eta: Duration? = null,
    val progress: Float? = null,
    val scheduledAt: Instant? = null,
    val error: DownloadError? = null,
    val retryCount: Int = 0,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val files: List<DownloadFileMetadata>? = null
)
