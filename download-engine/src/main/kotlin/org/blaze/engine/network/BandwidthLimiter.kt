package org.blaze.engine.network

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared leaky-bucket throttle for the whole engine.
 *
 * The previous implementation throttled each download on its own, so a "global" limit of
 * N KB/s actually allowed `maxConcurrentDownloads * N` KB/s to hit the wire. One instance is
 * owned by the [org.blaze.engine.core.DownloadManager] and handed to every executor, so the
 * ceiling is genuinely global and applies per connection worker too.
 *
 * Cost on the hot path is a couple of atomic operations per buffer, and the sleep happens
 * outside the accounting lock.
 */
class BandwidthLimiter {
    private val limitBytesPerSec = AtomicLong(0L)

    /** Nanosecond timestamp up to which transfer has already been "paid for". */
    private var paidUntilNs = 0L
    private val lock = Any()

    fun setLimit(bytesPerSecond: Long) {
        limitBytesPerSec.set(if (bytesPerSecond > 0) bytesPerSecond else 0L)
    }

    val isLimited: Boolean get() = limitBytesPerSec.get() > 0

    /** The ceiling currently in force in bytes/s; 0 means unlimited. Read by the UI and by the tests of the settings binding. */
    val limitBytesPerSecond: Long get() = limitBytesPerSec.get()

    /** Suspends until [bytes] may be transferred under the current global ceiling. */
    suspend fun acquire(bytes: Int) {
        val limit = limitBytesPerSec.get()
        if (limit <= 0 || bytes <= 0) return

        val waitMs = synchronized(lock) {
            val now = System.nanoTime()
            val floor = now - NANOS_PER_SECOND // never pay for more than one second of backlog
            if (paidUntilNs < floor) paidUntilNs = floor
            val permitNs = paidUntilNs + bytes * NANOS_PER_SECOND / limit
            val wait = (permitNs - now + 999_999L) / 1_000_000L
            paidUntilNs = permitNs
            wait
        }

        if (waitMs > 0) delay(waitMs.milliseconds)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
