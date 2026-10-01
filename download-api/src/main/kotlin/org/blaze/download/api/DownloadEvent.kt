package org.blaze.download.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Live notifications about download lifecycle, the alternative to clients polling.
 *
 * The set is small and stable; each event carries the [downloadId] and the minimum payload needed to
 * render the change. Progress events are emitted coalesced and rate-limited by the application service
 * so a chatty observer cannot overload the engine. Transports deliver these as a stream (in-process
 * [kotlinx.coroutines.flow.Flow], SSE, WebSocket, ...) without the producer knowing which.
 */
@Serializable
sealed interface DownloadEvent {
    val downloadId: String

    @Serializable
    @SerialName("created")
    data class Created(override val downloadId: String, val download: DownloadDto) : DownloadEvent

    @Serializable
    @SerialName("started")
    data class Started(override val downloadId: String) : DownloadEvent

    @Serializable
    @SerialName("progress")
    data class Progress(
        override val downloadId: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val speedBytesPerSecond: Long,
        val etaSeconds: Long?,
        val progress: Float?,
    ) : DownloadEvent

    @Serializable
    @SerialName("paused")
    data class Paused(override val downloadId: String) : DownloadEvent

    @Serializable
    @SerialName("resumed")
    data class Resumed(override val downloadId: String) : DownloadEvent

    @Serializable
    @SerialName("completed")
    data class Completed(override val downloadId: String, val download: DownloadDto) : DownloadEvent

    @Serializable
    @SerialName("failed")
    data class Failed(override val downloadId: String, val error: ApiErrorBody) : DownloadEvent

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(override val downloadId: String) : DownloadEvent

    @Serializable
    @SerialName("removed")
    data class Removed(override val downloadId: String) : DownloadEvent

    @Serializable
    @SerialName("errorChanged")
    data class ErrorChanged(override val downloadId: String, val error: ApiErrorBody?) : DownloadEvent
}
