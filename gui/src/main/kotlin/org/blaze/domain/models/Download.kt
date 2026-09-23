package org.blaze.domain.models

import org.blaze.download.api.ApiErrorBody
import org.blaze.download.api.DownloadPriority

data class Download(
    val id: String,
    val name: String,
    val url: String,
    val totalSize: Long?, // Nullable for unknown size
    val downloadedSize: Long,
    val speed: Long, // bytes per second
    val eta: Long? = null, // estimated time remaining in seconds
    val peers: Int = 0,
    val state: DownloadState,
    val addedAt: Long,
    val scheduledAt: Long? = null,
    val savePath: String,
    val error: ApiErrorBody? = null,
    val selectedFiles: List<SelectedFile>? = null,
    /**
     * Whether the source itself can continue from where a pause stopped. Null means unproven - only a
     * measured answer justifies warning the user, so "unknown" must never render as "impossible".
     */
    val resumeSupported: Boolean? = null,
    /** Scheduling importance; [DownloadPriority.NORMAL] matches the pre-priority FIFO behaviour. */
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val progress: Float = if (totalSize != null && totalSize > 0) {
        (downloadedSize.toFloat() / totalSize).coerceIn(0f, 1f)
    } else if (state == DownloadState.COMPLETED || state == DownloadState.SEEDING) {
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
    SEEDING,
    FAILED,
    REMOVING
}