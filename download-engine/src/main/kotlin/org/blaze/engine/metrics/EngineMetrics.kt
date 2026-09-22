package org.blaze.engine.metrics

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap, lock-free counters for the parts of the engine whose behavior is otherwise invisible in
 * logs: how many bytes actually crossed the wire, how often a segmented chunk had to be retried,
 * how often a stalled connection was dropped, how long the tail of the last transfer lingered on
 * the final connection, and how transfers ended.
 *
 * Everything here is an atomic increment (or a max/min update) on a path that already performs I/O,
 * so the cost is in the noise; nothing sits on the per-buffer hot path except [recordBytes].
 */
class EngineMetrics {
    private val bytesReceived = AtomicLong()
    private val chunkRetries = AtomicLong()
    private val stalledConnectionAborts = AtomicLong()
    private val completedTransfers = AtomicLong()
    private val failedTransfers = AtomicLong()
    private val resumedTransfers = AtomicLong()

    /** Largest worker pool any single segmented transfer actually ran with, over the engine's life. */
    private val peakWorkers = AtomicInteger(0)

    /** Wall time the most recent segmented transfer spent down to its last active connection. */
    private val lastTailMillis = AtomicLong(0)

    fun recordBytes(count: Long) = bytesReceived.addAndGet(count)
    fun recordChunkRetry() = chunkRetries.incrementAndGet()
    fun recordStalledWorkerDrop() = stalledConnectionAborts.incrementAndGet()
    fun recordCompleted() = completedTransfers.incrementAndGet()
    fun recordFailed() = failedTransfers.incrementAndGet()
    fun recordResumed() = resumedTransfers.incrementAndGet()

    fun recordPeakWorkers(count: Int) = peakWorkers.accumulateAndGet(count) { a, b -> maxOf(a, b) }

    /** Records (not accumulates) the tail duration of the transfer that just finished. */
    fun recordTailMillis(millis: Long) = lastTailMillis.set(millis.coerceAtLeast(0))

    fun snapshot(): DownloadDiagnostics = DownloadDiagnostics(
        bytesReceived = bytesReceived.get(),
        chunkRetries = chunkRetries.get(),
        stalledWorkerDrops = stalledConnectionAborts.get(),
        completedTransfers = completedTransfers.get(),
        failedTransfers = failedTransfers.get(),
        resumedTransfers = resumedTransfers.get(),
        peakWorkers = peakWorkers.get(),
        lastTailMillis = lastTailMillis.get()
    )
}

/**
 * A point-in-time view of engine behavior. [stalledWorkerDrops] is kept as the field name for API
 * stability even though the recovery now aborts the stalled *request* rather than dropping the
 * worker; [lastTailMillis] and [peakWorkers] expose the shape of a transfer's finish, which is what
 * tail-latency and concurrency diagnostics need.
 */
data class DownloadDiagnostics(
    val bytesReceived: Long = 0,
    val chunkRetries: Long = 0,
    val stalledWorkerDrops: Long = 0,
    val completedTransfers: Long = 0,
    val failedTransfers: Long = 0,
    val resumedTransfers: Long = 0,
    val peakWorkers: Int = 0,
    val lastTailMillis: Long = 0
)
