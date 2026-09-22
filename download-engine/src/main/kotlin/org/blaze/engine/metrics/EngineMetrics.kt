package org.blaze.engine.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap, lock-free counters for the parts of the engine whose behavior is otherwise invisible in
 * logs: how many bytes actually crossed the wire, how often a segmented chunk had to be retried,
 * how often a stalled connection was dropped, and how transfers ended.
 *
 * Everything is an [AtomicLong] increment on a path that already performs I/O, so the cost is in
 * the noise; nothing here is on the per-buffer hot path except [recordBytes].
 */
class EngineMetrics {
    private val bytesReceived = AtomicLong()
    private val chunkRetries = AtomicLong()
    private val stalledWorkerDrops = AtomicLong()
    private val completedTransfers = AtomicLong()
    private val failedTransfers = AtomicLong()
    private val resumedTransfers = AtomicLong()

    fun recordBytes(count: Long) = bytesReceived.addAndGet(count)
    fun recordChunkRetry() = chunkRetries.incrementAndGet()
    fun recordStalledWorkerDrop() = stalledWorkerDrops.incrementAndGet()
    fun recordCompleted() = completedTransfers.incrementAndGet()
    fun recordFailed() = failedTransfers.incrementAndGet()
    fun recordResumed() = resumedTransfers.incrementAndGet()

    fun snapshot(): DownloadDiagnostics = DownloadDiagnostics(
        bytesReceived = bytesReceived.get(),
        chunkRetries = chunkRetries.get(),
        stalledWorkerDrops = stalledWorkerDrops.get(),
        completedTransfers = completedTransfers.get(),
        failedTransfers = failedTransfers.get(),
        resumedTransfers = resumedTransfers.get()
    )

}

data class DownloadDiagnostics(
    val bytesReceived: Long = 0,
    val chunkRetries: Long = 0,
    val stalledWorkerDrops: Long = 0,
    val completedTransfers: Long = 0,
    val failedTransfers: Long = 0,
    val resumedTransfers: Long = 0
)
