package org.blaze.engine.retry

import org.blaze.engine.api.DownloadError
import org.blaze.engine.settings.DownloadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Retrying is a rate limiter against the server as much as a recovery tool: a 404 or a full disk
 * will not fix itself, and hammering a host that answered 401 is worse than failing early.
 */
class RetryPolicyClassificationTest {

    private val policy = DefaultRetryPolicy(DownloadSettings(maxRetries = 3, retryDelaySeconds = 1))

    @Test
    fun `permanent failures end the download immediately`() {
        val permanent = listOf(
            DownloadError.NotFound,
            DownloadError.Unauthorized,
            DownloadError.DiskFull,
            DownloadError.InvalidTorrent,
            DownloadError.RangeUnsupported,
            DownloadError.Cancelled,
            DownloadError.NetworkFailure("Server returned HTTP 404"),
            DownloadError.NetworkFailure("Server returned HTTP 403"),
            DownloadError.NetworkFailure("Server returned HTTP 410"),
            DownloadError.NetworkFailure("Server returned HTTP 501")
        )

        permanent.forEach { error ->
            assertNull(policy.getNextDelay(error, retryCount = 0), "retried a permanent failure: $error")
        }
    }

    @Test
    fun `transient failures retry until the budget is spent`() {
        val transient = listOf(
            DownloadError.Timeout,
            DownloadError.NetworkFailure("Connection reset by peer"),
            DownloadError.NetworkFailure("Server returned HTTP 500"),
            DownloadError.NetworkFailure("Server returned HTTP 503"),
            DownloadError.NetworkFailure("Redirect chain changed mid-transfer")
        )

        transient.forEach { error ->
            assertNotNull(policy.getNextDelay(error, retryCount = 0), "refused to retry: $error")
            assertNotNull(policy.getNextDelay(error, retryCount = 2), "gave up early: $error")
            assertNull(policy.getNextDelay(error, retryCount = 3), "ignored the retry budget: $error")
        }
    }

    @Test
    fun `backoff doubles the wait when it is enabled`() {
        val backoff = DefaultRetryPolicy(DownloadSettings(maxRetries = 5, retryDelaySeconds = 2, exponentialBackoff = true))

        assertEquals(2_000L, backoff.getNextDelay(DownloadError.Timeout, 0))
        assertEquals(4_000L, backoff.getNextDelay(DownloadError.Timeout, 1))
        assertEquals(8_000L, backoff.getNextDelay(DownloadError.Timeout, 2))
    }

    @Test
    fun `disabling auto retry wins over every other rule`() {
        val off = DefaultRetryPolicy(DownloadSettings(autoRetryFailed = false, maxRetries = 5))

        assertNull(off.getNextDelay(DownloadError.Timeout, 0))
    }
}
