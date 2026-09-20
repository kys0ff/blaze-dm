package org.blaze.domain.models

import org.blaze.engine.api.DownloadError

data class Download(
    val id: String,
    val name: String,
    val url: String,
    val totalSize: Long?, // Nullable for unknown size
    val downloadedSize: Long,
    val speed: Long, // bytes per second
    val peers: Int = 0,
    val state: DownloadState,
    val addedAt: Long,
    val savePath: String,
    val error: DownloadError? = null,
    val selectedFiles: List<SelectedFile>? = null,
    val progress: Float = if (totalSize != null && totalSize > 0) {
        (downloadedSize.toFloat() / totalSize).coerceIn(0f, 1f)
    } else if (state == DownloadState.COMPLETED) {
        1f
    } else {
        0f
    }
)

data class SelectedFile(
    val path: String,
    val size: Long,
    val index: Int
)

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    REMOVING
}