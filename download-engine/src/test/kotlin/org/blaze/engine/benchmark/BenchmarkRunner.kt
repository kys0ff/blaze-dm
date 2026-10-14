package org.blaze.engine.benchmark

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Small measurement harness so every Phase-3 before/after number comes from the same recipe.
 *
 * PROMPT.md §D asks for warm-up, several repetitions, median and spread, plus resource metrics
 * that make a claim of "faster" falsifiable: total time, throughput, TTFB, GC pause time and count,
 * peak heap usage, thread count and (for HTTP) server-side connection count.
 *
 * A "run" is [Measurement]; a benchmark is a sequence of measurements that yields a [Summary]. The
 * runner is deliberately *not* statistical - no percentiles, no standard deviations beyond a simple
 * max/min spread. Anything fancier needs to be argued for, and Phase 3 mostly wants to answer "did
 * this change help or hurt and by how much".
 */
object BenchmarkRunner {

    /** What one run of any benchmark has to report. */
    data class Measurement(
        val label: String,
        val wallMillis: Double,
        val bytes: Long,
        val ttfbMillis: Double? = null,
        val extra: Map<String, Any> = emptyMap(),
    ) {
        val mebibytesPerSecond: Double
            get() = if (wallMillis <= 0) Double.NaN else bytes / 1048576.0 / (wallMillis / 1000.0)
    }

    /** Aggregate of [Measurement] samples after warm-up. */
    data class Summary(
        val label: String,
        val samples: List<Measurement>,
        val warmups: Int,
        val medianWallMillis: Double,
        val minWallMillis: Double,
        val maxWallMillis: Double,
        val medianMebibytesPerSecond: Double,
        val gcPauseMillis: Double,
        val gcCollections: Long,
        val peakHeapBytes: Long,
        val peakThreads: Int,
    ) {
        val spread: String get() = "min=%.1f ms, median=%.1f ms, max=%.1f ms".format(minWallMillis, medianWallMillis, maxWallMillis)

        fun render(): String = buildString {
            appendLine(label + "  [warmups=" + warmups + ", samples=" + samples.size + "]")
            appendLine("  throughput : " + "%.2f MiB/s (median)".format(medianMebibytesPerSecond))
            appendLine("  wall time  : " + spread)
            appendLine("  gc         : " + "%.1f ms across %d collections".format(gcPauseMillis, gcCollections))
            appendLine(
                "  peak heap  : " + "%.1f MiB, peak threads: %d".format(peakHeapBytes / 1048576.0, peakThreads)
            )
            samples.firstOrNull()?.ttfbMillis?.let {
                appendLine("  ttfb       : " + "%.1f ms (first sample)".format(it))
            }
            for (extra in samples.firstOrNull()?.extra.orEmpty()) {
                appendLine("  " + extra.key + " = " + extra.value)
            }
        }
    }

    /**
     * Runs [block] [warmups] + [samples] times, discards the warm-ups and reports a [Summary] of the
     * remaining samples. [block] receives a monotonically increasing run index so a benchmark can
     * rotate state (e.g. seed the payload) between iterations if it wants.
     */
    fun run(
        label: String,
        warmups: Int = 1,
        samples: Int = 5,
        block: (runIndex: Int) -> Measurement,
    ): Summary {
        require(warmups >= 0) { "warmups must be non-negative, was $warmups" }
        require(samples >= 1) { "at least one sample is required, was $samples" }
        val gcBefore = GcSnapshot.take()
        repeat(warmups) { block(it) }
        val collected = ArrayList<Measurement>(samples)
        var peakHeap = 0L
        var peakThreads = 0
        for (i in 0 until samples) {
            val m = block(warmups + i)
            collected += m
            peakHeap = maxOf(peakHeap, usedHeapBytes())
            peakThreads = maxOf(peakThreads, liveThreadCount())
        }
        val gcAfter = GcSnapshot.take()
        val sorted = collected.sortedBy { it.wallMillis }
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].wallMillis
        else (sorted[sorted.size / 2 - 1].wallMillis + sorted[sorted.size / 2].wallMillis) / 2.0
        val medianThroughput = if (median > 0) {
            collected.sumOf { it.bytes.toDouble() } / collected.size / 1048576.0 / (median / 1000.0)
        } else Double.NaN
        return Summary(
            label = label,
            samples = collected,
            warmups = warmups,
            medianWallMillis = median,
            minWallMillis = sorted.first().wallMillis,
            maxWallMillis = sorted.last().wallMillis,
            medianMebibytesPerSecond = medianThroughput,
            gcPauseMillis = gcAfter.totalPauseMillis - gcBefore.totalPauseMillis,
            gcCollections = gcAfter.totalCollections - gcBefore.totalCollections,
            peakHeapBytes = peakHeap,
            peakThreads = peakThreads,
        )
    }

    /** Runs one download-style operation and reports a [Measurement] with sensible timing. */
    class Timer(val label: String, val bytes: Long) {
        private val start = System.nanoTime()
        private val firstByteAt = AtomicLong(-1L)
        fun markFirstByte() { firstByteAt.compareAndSet(-1L, System.nanoTime()) }
        fun stop(ttfbMillisOverride: Double? = null, extra: Map<String, Any> = emptyMap()): Measurement {
            val elapsedNanos = System.nanoTime() - start
            val ttfb = ttfbMillisOverride ?: firstByteAt.get().takeIf { it >= 0 }?.let { (it - start) / 1e6 }
            return Measurement(
                label = label,
                wallMillis = elapsedNanos / 1e6,
                bytes = bytes,
                ttfbMillis = ttfb,
                extra = extra,
            )
        }
    }

    fun timer(label: String, bytes: Long): Timer = Timer(label, bytes)

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun liveThreadCount(): Int = Thread.getAllStackTraces().size

    /** Snapshot of GC counters across all collectors, so we can report deltas per benchmark run. */
    private data class GcSnapshot(val totalPauseMillis: Double, val totalCollections: Long) {
        companion object {
            fun take(): GcSnapshot {
                var pause = 0.0
                var count = 0L
                for (b in garbageCollectors) {
                    pause += b.collectionTime.toDouble()
                    count += b.collectionCount
                }
                return GcSnapshot(pause, count)
            }

            private val garbageCollectors: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()
        }
    }
}
