package org.blaze.engine.retry

import org.blaze.engine.api.DownloadError
import org.blaze.engine.settings.DownloadSettings

interface RetryPolicy {
    fun getNextDelay(error: DownloadError, retryCount: Int): Long?
}

class DefaultRetryPolicy(private val settings: DownloadSettings) : RetryPolicy {
    override fun getNextDelay(error: DownloadError, retryCount: Int): Long? {
        if (!settings.autoRetryFailed) return null
        if (error is DownloadError.Cancelled) return null
        if (retryCount >= settings.maxRetries) return null
        return settings.retryDelaySeconds.toLong() * 1000
    }
}
