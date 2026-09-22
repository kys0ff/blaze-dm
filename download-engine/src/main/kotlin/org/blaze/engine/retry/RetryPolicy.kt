package org.blaze.engine.retry

import org.blaze.engine.api.DownloadError
import org.blaze.engine.settings.DownloadSettings

interface RetryPolicy {
    fun getNextDelay(error: DownloadError, retryCount: Int): Long?
}

/**
 * Retrying is only useful while the failure could plausibly fix itself.
 *
 * A 404, a rejected credential, a full disk or a corrupt .torrent will fail exactly the same way
 * on the fifth attempt, and hammering a server that answered 401 wastes the user's quota - so
 * those end the download immediately instead of burning the retry budget.
 */
fun DownloadError.isRetryable(): Boolean = when (this) {
    DownloadError.Cancelled,
    DownloadError.NotFound,
    DownloadError.Unauthorized,
    DownloadError.DiskFull,
    DownloadError.InvalidTorrent,
    DownloadError.RangeUnsupported -> false

    is DownloadError.NetworkFailure -> !message.looksPermanent()
    else -> true
}

private fun String.looksPermanent(): Boolean = PERMANENT_HINTS.any { contains(it, ignoreCase = true) }

private val PERMANENT_HINTS = listOf(
    "HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404", "HTTP 405", "HTTP 409", "HTTP 410",
    "HTTP 411", "HTTP 412", "HTTP 414", "HTTP 416", "HTTP 451", "HTTP 501", "HTTP 505"
)

class DefaultRetryPolicy(private val settings: DownloadSettings) : RetryPolicy {
    override fun getNextDelay(error: DownloadError, retryCount: Int): Long? {
        if (!settings.autoRetryFailed) return null
        if (!error.isRetryable()) return null
        if (retryCount >= settings.maxRetries) return null
        val baseDelayMs = settings.retryDelaySeconds.toLong() * 1000
        return if (settings.exponentialBackoff) {
            // Double the wait on every successive attempt: base * 2^retryCount.
            // retryCount is capped below maxRetries (>= 0), so the shift stays in range.
            baseDelayMs shl retryCount.coerceAtMost(16)
        } else {
            baseDelayMs
        }
    }
}
