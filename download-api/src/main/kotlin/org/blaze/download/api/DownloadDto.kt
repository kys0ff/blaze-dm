package org.blaze.download.api

import kotlinx.serialization.Serializable

/**
 * A safe, display-only description of where a download comes from.
 *
 * It deliberately carries only a human-meaningful [locator] (the URL, magnet URI or torrent name) and
 * never any request headers, cookies or tokens - those live only in the transient engine request and
 * are not part of this contract. [mayRequireCredentials] is a boolean hint so a client knows a resume
 * might need fresh authentication, without any secret material being exposed.
 */
@Serializable
data class SourceInfo(
    val kind: SourceKind,
    val locator: String,
    val mayRequireCredentials: Boolean = false,
)

/** One file inside a (multi-file) torrent download. */
@Serializable
data class DownloadFile(
    val path: String,
    val size: Long,
    val selected: Boolean = true,
)

/**
 * The client-safe, transport-agnostic view of a single download.
 *
 * This is what every client - GUI, CLI, browser extension - reads. It is a stable serialization
 * surface: field names here are the wire contract. It exposes only information that is safe to hand
 * to an external caller; authentication material is never represented in this type.
 *
 * [progress] and [totalBytes] are nullable because a source may not know its size up front.
 * [averageSpeedBytesPerSecond] is derived (total downloaded / elapsed active time) and may be null
 * until enough data has flowed to be meaningful.
 */
@Serializable
data class DownloadDto(
    val id: String,
    val name: String,
    val state: DownloadState,
    val source: SourceInfo,
    val destination: String,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val progress: Float? = null,
    val speedBytesPerSecond: Long = 0,
    val averageSpeedBytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val peers: Int = 0,
    val error: ApiErrorBody? = null,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val scheduledAtEpochMs: Long? = null,
    val resumable: Boolean = false,
    val portable: Boolean = false,
    val files: List<DownloadFile>? = null,
)
