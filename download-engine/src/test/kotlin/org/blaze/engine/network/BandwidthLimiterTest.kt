package org.blaze.engine.network

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine used to throttle every download on its own, so a "global" limit of N KB/s let
 * `maxConcurrentDownloads * N` KB/s through. These tests pin the property that was actually
 * promised: one ceiling, shared by everyone, enforced by time rather than by polling.
 */
class BandwidthLimiterTest {

    private fun measureThroughput(limitBytesPerSec: Long, workers: Int, totalBytes: Int): Long = runBlocking {
        val limiter = BandwidthLimiter().apply { setLimit(limitBytesPerSec) }
        val delivered = AtomicLong(0)
        val started = System.nanoTime()

        withTimeout(30.seconds) {
            coroutineScope {
                repeat(workers) {
                    launch {
                        var sent = 0
                        while (sent < totalBytes / workers) {
                            val chunk = minOf(64 * 1024, totalBytes / workers - sent)
                            if (chunk <= 0) break
                            limiter.acquire(chunk)
                            delivered.addAndGet(chunk.toLong())
                            sent += chunk
                        }
                    }
                }
            }
        }
        val elapsedNs = System.nanoTime() - started
        (delivered.get() * 1_000_000_000L / elapsedNs.coerceAtLeast(1)).coerceAtLeast(1)
    }

    @Test
    fun `an unlimited limiter does not throttle`(): Unit = runBlocking {
        val limiter = BandwidthLimiter()
        assertFalse(limiter.isLimited)
        val started = System.nanoTime()
        repeat(10_000) { limiter.acquire(1024) }
        assertTrue(System.nanoTime() - started < 1.seconds.inWholeNanoseconds, "unlimited acquire was slow")
    }

    @Test
    fun `throughput respects the ceiling`() {
        val rate = measureThroughput(limitBytesPerSec = 200 * 1024, workers = 1, totalBytes = 600 * 1024)
        // 600 KiB at 200 KiB/s takes ~3 s; allow generous slack for scheduler jitter.
        assertTrue(rate <= 320 * 1024, "exceeded the limit: $rate B/s")
        assertTrue(rate >= 100 * 1024, "throttled far below the limit: $rate B/s")
    }

    @Test
    fun `the ceiling is shared across every worker`() {
        // Four independent streams must still land near the limit in total, not 4x above it.
        val rate = measureThroughput(limitBytesPerSec = 200 * 1024, workers = 4, totalBytes = 800 * 1024)
        assertTrue(rate <= 320 * 1024, "workers together exceeded the global limit: $rate B/s")
    }

    @Test
    fun `changing the limit takes effect on the next acquire`(): Unit = runBlocking {
        val limiter = BandwidthLimiter().apply { setLimit(64 * 1024) }
        // A fresh bucket hands out up to a second of allowance as burst credit, so the wait has to
        // be measured on the request that follows the burst.
        limiter.acquire(64 * 1024)
        val started = System.nanoTime()
        limiter.acquire(32 * 1024)
        val blockedNs = System.nanoTime() - started
        assertTrue(blockedNs > 100.milliseconds.inWholeNanoseconds, "expected the small limit to block")

        limiter.setLimit(0)
        assertFalse(limiter.isLimited)
        val unlimitedStart = System.nanoTime()
        repeat(1000) { limiter.acquire(64 * 1024) }
        assertTrue(
            System.nanoTime() - unlimitedStart < 100.milliseconds.inWholeNanoseconds,
            "acquire still blocked after the limit was lifted"
        )
    }
}
