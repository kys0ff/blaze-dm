package org.blaze.presentation.util

import org.blaze.engine.api.DownloadError
import kotlin.math.ln
import kotlin.math.pow

internal fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "Unknown"
    if (bytes == 0L) return "0 B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    if (exp == 0) return "$bytes B"
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
}

internal fun formatSpeed(bytesPerSec: Long): String {
    return formatSize(bytesPerSec) + "/s"
}

internal fun DownloadError.toFriendlyMessage(): String = when (this) {
    DownloadError.NetworkUnavailable -> "Network unavailable"
    DownloadError.Timeout -> "Connection timed out"
    DownloadError.Unauthorized -> "Unauthorized access"
    DownloadError.NotFound -> "File not found"
    DownloadError.DiskFull -> "Disk full"
    DownloadError.RangeUnsupported -> "Resuming not supported"
    DownloadError.InvalidTorrent -> "Invalid torrent file"
    DownloadError.MetadataTimeout -> "Failed to fetch metadata"
    DownloadError.Cancelled -> "Download cancelled"
    is DownloadError.NetworkFailure -> "Network failure: $message"
    is DownloadError.FileSystemError -> "Disk error: $message"
    is DownloadError.Unknown -> "Unknown error: $message"
}
