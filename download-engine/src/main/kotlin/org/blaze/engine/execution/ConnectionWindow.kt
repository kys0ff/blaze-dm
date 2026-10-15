package org.blaze.engine.execution

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A resizable concurrency gate for one segmented transfer's connection workers.
 *
 * It starts allowing [max] simultaneous network requests and sheds load when the host pushes back:
 * every throttling response (429 / 503) roughly halves the window - at most once per [reduceCooldownNanos]
 * so a burst of simultaneous 429s across workers does not collapse straight to one - and a run of
 * [recoverySuccesses] consecutive clean chunks wins a single permit back, up to [max]. That asymmetry
 * (drop fast, recover slow) is the hysteresis: it stops the window oscillating around the point where
 * the host starts to complain, which a symmetric rule would.
 *
 * When the host never throttles, [acquire] is a pure fast-path counter check and never parks, so this
 * is a no-op gate for a healthy transfer - the point is to bend down under pressure, not to tax normal
 * downloads.
 *
 * [clock] is injectable so the reduce/grow *policy* is unit-testable without wall-clock timing; the
 * default is the monotonic nano timer the waiting loop already uses.
 */
class ConnectionWindow(
    val max: Int,
    private val reduceCooldownNanos: Long = DEFAULT_REDUCE_COOLDOWN_NS,
    private val recoverySuccesses: Int = DEFAULT_RECOVERY_SUCCESSES,
    private val pollNanos: Long = DEFAULT_POLL_NS,
    private val clock: () -> Long = System::nanoTime,
    private val onChange: (Int) -> Unit = {},
) {
    init {
        require(max >= 1) { "max connections must be at least 1, was $max" }
        require(recoverySuccesses >= 1) { "recoverySuccesses must be at least 1, was $recoverySuccesses" }
    }

    /** The number of requests currently allowed to be in flight at once. */
    val allowed: Int get() = window.get()

    private val window = AtomicInteger(max)
    private val inFlight = AtomicInteger(0)
    private val lastReduceNs = AtomicLong(Long.MIN_VALUE)
    private val cleanSuccesses = AtomicInteger(0)

    /**
     * Blocks until a request slot is free under the current window, then reserves it. Pairs with
     * [release] in a `try/finally`. The busy-poll interval only bites when the window is already
     * reduced below the worker count - i.e. precisely when we want to slow down - so it never taxes
     * the healthy path.
     */
    suspend fun acquire() {
        // Reserve optimistically, then wait for the window to open if we overshoot. Waiting *after*
        // incrementing keeps the reservation race-free: two workers cannot both see room and pass.
        while (true) {
            val now = inFlight.incrementAndGet()
            if (now <= window.get()) return
            inFlight.decrementAndGet()
            delay((pollNanos / 1_000_000L).coerceAtLeast(1L))
        }
    }

    /** Gives back a slot reserved by [acquire]. */
    fun release() {
        inFlight.decrementAndGet()
    }

    /**
     * Records a throttling response (429 / 503). Halves the window down to a floor of one, but only
     * if the cooldown since the last reduction has elapsed - so a whole pool of workers hitting the
     * same 429 in the same instant sheds just one step, not one step each.
     */
    fun noteThrottle() {
        cleanSuccesses.set(0)
        reduceLocked()
    }

    /**
     * Records a clean chunk completion. After enough consecutive ones the window grows back a single
     * step (never more), restoring throughput gradually rather than snapping to full width.
     */
    fun noteSuccess() {
        if (window.get() >= max) {
            cleanSuccesses.set(0)
            return
        }
        if (cleanSuccesses.incrementAndGet() >= recoverySuccesses) {
            cleanSuccesses.set(0)
            val grown = window.accumulateAndGet(max) { current, cap -> minOf(cap, current + 1) }
            onChange(grown)
        }
    }

    private fun reduceLocked() {
        while (true) {
            val current = window.get()
            if (current <= 1) return
            val now = clock()
            val last = lastReduceNs.get()
            // The initial sentinel (Long.MIN_VALUE) means "never reduced yet", so the very first
            // throttle always reduces; comparing against it with subtraction would overflow and
            // could wrongly look "within cooldown". Subsequent reductions honour the real gap.
            if (last != Long.MIN_VALUE && now - last < reduceCooldownNanos) return
            if (!lastReduceNs.compareAndSet(last, now)) continue
            val next = (current / 2).coerceAtLeast(1)
            if (window.compareAndSet(current, next)) {
                onChange(next)
                return
            }
        }
    }

    companion object {
        private const val DEFAULT_REDUCE_COOLDOWN_NS = 2_000_000_000L // 2 s
        private const val DEFAULT_RECOVERY_SUCCESSES = 4
        private const val DEFAULT_POLL_NS = 25_000_000L // 25 ms

        /** Minimum window any transfer may be shed down to - one connection keeps it making progress. */
        const val MIN_WINDOW = 1
    }
}
