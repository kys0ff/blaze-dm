package org.blaze.presentation.util

import org.blaze.engine.api.DownloadError
import org.blaze.i18n.BlazeStrings
import kotlin.math.ln
import kotlin.math.pow

internal fun formatSize(bytes: Long?, strings: BlazeStrings): String {
    if (bytes == null || bytes < 0) return strings.common.unknown
    if (bytes == 0L) return "0 B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    if (exp == 0) return "$bytes B"
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
}

internal fun formatSpeed(bytesPerSec: Long, strings: BlazeStrings): String {
    return formatSize(bytesPerSec, strings) + "/s"
}

internal fun DownloadError.toFriendlyMessage(strings: BlazeStrings): String = when (this) {
    DownloadError.NetworkUnavailable -> strings.errors.networkUnavailable
    DownloadError.Timeout -> strings.errors.timeout
    DownloadError.Unauthorized -> strings.errors.unauthorized
    DownloadError.NotFound -> strings.errors.notFound
    DownloadError.DiskFull -> strings.errors.diskFull
    DownloadError.RangeUnsupported -> strings.errors.rangeUnsupported
    DownloadError.InvalidTorrent -> strings.errors.invalidTorrent
    DownloadError.MetadataTimeout -> strings.errors.metadataTimeout
    DownloadError.Cancelled -> strings.errors.cancelled
    is DownloadError.NetworkFailure -> strings.errors.networkFailure(message)
    is DownloadError.FileSystemError -> strings.errors.diskError(message)
    is DownloadError.Unknown -> strings.errors.unknown(message)
}
