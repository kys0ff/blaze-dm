package org.blaze.service.mapping

import org.blaze.download.api.DownloadDto
import org.blaze.download.api.DownloadFile
import org.blaze.download.api.SourceInfo
import org.blaze.download.api.SourceKind
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.download.api.DownloadPriority as ApiPriority
import org.blaze.engine.api.DownloadPriority as EnginePriority
import org.blaze.engine.api.DownloadState as EngineState

/**
 * Converts an engine [DownloadTask] into the client-safe [DownloadDto].
 *
 * Only non-sensitive fields are copied. Request headers, cookies and credentials are never read here -
 * the DTO's [SourceInfo] carries a display locator only - which is what keeps authentication material out
 * of every response, event and log. [averageSpeedBytesPerSecond] and [DownloadDto.resumable] are derived
 * because the engine does not surface them directly.
 */
fun DownloadTask.toDto(): DownloadDto {
    val now = System.currentTimeMillis()
    val publicState = state.toPublic()

    val selectedIndices = (request as? DownloadRequest.Torrent)?.fileIndices?.toSet()
    val files = this.files?.mapIndexed { index, f ->
        DownloadFile(
            path = f.path,
            size = f.size,
            selected = selectedIndices == null || selectedIndices.contains(index),
        )
    }

    val elapsedMs = (now - createdAt.toEpochMilli()).coerceAtLeast(1L)
    val average = if (downloadedBytes > 0 && publicState.isActive) {
        downloadedBytes * 1000L / elapsedMs
    } else {
        null
    }

    val resumable = publicState.let {
        it == org.blaze.download.api.DownloadState.PAUSED ||
            it == org.blaze.download.api.DownloadState.FAILED ||
            it == org.blaze.download.api.DownloadState.QUEUED
    }

    return DownloadDto(
        id = id.value,
        name = name,
        state = publicState,
        source = request.toSourceInfo(),
        destination = request.destination.toAbsolutePath().normalize().toString(),
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        progress = progress ?: totalBytes?.let { if (it > 0) (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) else null },
        speedBytesPerSecond = downloadSpeed.coerceAtLeast(0),
        averageSpeedBytesPerSecond = average,
        etaSeconds = eta?.inWholeSeconds,
        peers = peers,
        error = error?.toApiError(),
        createdAtEpochMs = createdAt.toEpochMilli(),
        completedAtEpochMs = completedAt?.toEpochMilli(),
        scheduledAtEpochMs = scheduledAt?.toEpochMilli(),
        resumable = resumable,
        // What the transfer proved about the source, not a state guess: null keeps "we don't know"
        // distinct from "this host cannot resume", which is what a client warns about.
        sourceSupportsResume = resumeSupported,
        restarts = restarts,
        portable = false,
        files = files,
        priority = priority.toApi(),
    )
}

/** The two enums share their constant names, so the mapping is a straight rename. */
private fun EnginePriority.toApi(): ApiPriority = ApiPriority.valueOf(name)

private fun DownloadRequest.toSourceInfo(): SourceInfo = when (this) {
    is DownloadRequest.Http -> SourceInfo(
        kind = if (url.startsWith("https", ignoreCase = true)) SourceKind.HTTPS else SourceKind.HTTP,
        locator = url,
        mayRequireCredentials = headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) },
    )
    is DownloadRequest.Torrent -> when (val s = torrentSource) {
        is TorrentSource.Magnet -> SourceInfo(SourceKind.MAGNET, s.uri)
        // A cached metainfo file keeps pointing at whatever the user queued: the internal cache
        // path is an implementation detail and must never be the copied/shown source.
        is TorrentSource.File -> when (val origin = s.origin) {
            is TorrentSource.Magnet -> SourceInfo(SourceKind.MAGNET, origin.uri)
            is TorrentSource.Url -> SourceInfo(SourceKind.TORRENT_URL, origin.url)
            else -> SourceInfo(SourceKind.TORRENT_FILE, s.path.fileName?.toString() ?: s.path.toString())
        }
        is TorrentSource.Url -> SourceInfo(SourceKind.TORRENT_URL, s.url)
    }
    is DownloadRequest.Hls -> SourceInfo(
        kind = SourceKind.HLS,
        locator = playlistUrl,
        mayRequireCredentials = headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) },
    )

    is DownloadRequest.Archive -> SourceInfo(
        kind = SourceKind.ZIP_ARCHIVE,
        locator = url,
        mayRequireCredentials = headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) },
    )
}

/** True when the task is in a terminal state, used by the event diff to decide completion emissions. */
fun DownloadTask.isTerminal(): Boolean = state == EngineState.Completed ||
    state == EngineState.Failed ||
    state == EngineState.Cancelled
