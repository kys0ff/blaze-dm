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
    val files: List<DownloadFileMetadata>? = null,
    /**
     * Scheduling importance. Drives queue order and the transfer's connection share; see
     * [DownloadPriority]. Defaults to [DownloadPriority.NORMAL], which preserves the old FIFO
     * behaviour for anything that never sets it explicitly.
     */
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    /**
     * Whether the source can serve the ranged requests a resume depends on, as proven by the
     * transfer itself. Null while unknown: a paused download whose value is still null may or may
     * not continue where it stopped, and no client should claim otherwise.
     */
    val resumeSupported: Boolean? = null,
    /**
     * How many times the engine has genuinely thrown away downloaded bytes and restarted the
     * transfer from byte 0 (a refused range, an unusable resume record). It only ever moves forward,
     * so a client can detect a real lost-progress event by watching it increment - and stays silent
     * for the byte-counter dips of a chunk retry or a pause/resume re-anchor, which never touch it.
     */
    val restarts: Int = 0
)
