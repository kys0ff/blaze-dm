package org.blaze.presentation.util

import org.blaze.download.api.ApiErrorBody
import org.blaze.download.api.ApiErrorCode
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

/**
 * A transfer rate: the humanized size with a localized per-second suffix. The suffix comes from
 * [BlazeStrings.downloads] `speed` (which appends "/s" / "ث"), so this is the *only* place a rate gets
 * its unit — callers must pass the result straight through, never wrap it in `speed()` again
 * (that double-appended the suffix and produced the "MB/s/s" status-bar bug).
 */
internal fun formatSpeed(bytesPerSec: Long, strings: BlazeStrings): String =
    strings.downloads.speed(formatSize(bytesPerSec, strings))

internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "00:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

/**
 * Turns a client-safe [ApiErrorBody] into a localized sentence.
 *
 * The GUI switches on the stable [ApiErrorCode] the application API exposes - never an engine exception
 * type - so the wording stays meaningful even if the transfer implementation behind the API changes.
 */
internal fun ApiErrorBody.toFriendlyMessage(strings: BlazeStrings): String = when (code) {
    ApiErrorCode.NETWORK_ERROR -> strings.errors.networkFailure(message)
    ApiErrorCode.AUTH_REQUIRED, ApiErrorCode.PERMISSION_DENIED -> strings.errors.unauthorized
    ApiErrorCode.DOWNLOAD_NOT_FOUND -> strings.errors.notFound
    ApiErrorCode.INVALID_URL -> strings.errors.notFound
    ApiErrorCode.DESTINATION_UNAVAILABLE -> strings.errors.diskError(message)
    ApiErrorCode.INVALID_TORRENT -> strings.errors.invalidTorrent
    ApiErrorCode.RATE_LIMITED, ApiErrorCode.SERVICE_UNAVAILABLE -> strings.errors.timeout
    else -> strings.errors.unknown(message)
}
