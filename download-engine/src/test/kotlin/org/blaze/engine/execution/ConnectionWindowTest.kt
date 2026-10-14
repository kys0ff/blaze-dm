package org.blaze.engine.execution

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 throughput-policy tests for the adaptive connection window.
 *
 * The whole point of these is that the reduce/grow behaviour is *deterministic* and independent of
 * wall-clock timing: the window is built with an injectable nano clock so a test can advance "time"
 * to cross the reduce cooldown without actually sleeping. What is left to the coroutine scheduler is
 * only the acquire/release gate, which is checked with a counted in-flight probe rather than timing.
 */
class ConnectionWindowTest {

    /** A manually advanced nano clock; the test decides when the cooldown has elapsed. */
    private class FakeClock(start: Long = 0L) {
        private val now = AtomicInteger(0)
        private val base = start
        operator fun invoke(): Long = base + now.get() * 1_000_000L // each tick = 1 ms
        fun advance(millis: Long) { now.set(now.get() + millis.toInt()) }
    }

    @Test
    fun `window starts wide and the first throttle always halves it`() {
        val clock = FakeClock()
        val window = ConnectionWindow(max = 8, reduceCooldownNanos = 2_000_000_000L, clock = clock::invoke)
        assertEquals(8, window.allowed, "an untouched window must allow the full pool")

        window.noteThrottle()
        assertEquals(4, window.allowed, "first 429 / 503 must shed half the connections")
    }

    @Test
    fun `throttles within the cooldown collapse to a single step, not one per event`() {
        val clock = FakeClock()
        val window = ConnectionWindow(max = 8, reduceCooldownNanos = 2_000_000_000L, clock = clock::invoke)

        // A whole pool of workers observing the same 429 in the same instant must not march
        // 8 -> 4 -> 2 -> 1; only the first reduction inside the cooldown is honoured.
        repeat(5) { window.noteThrottle() }
        assertEquals(4, window.allowed, "repeat throttles inside the cooldown must not compound")

        // Once the cooldown elapses, the next throttle is allowed to shed another step.
        clock.advance(2_000)
        window.noteThrottle()
        assertEquals(2, window.allowed, "after the cooldown a fresh throttle sheds again")
    }

    @Test
    fun `the window never sheds below one connection`() {
        val clock = FakeClock()
        val window = ConnectionWindow(max = 4, reduceCooldownNanos = 1L, clock = clock::invoke)
        repeat(10) {
            window.noteThrottle()
            clock.advance(10)
        }
        assertEquals(ConnectionWindow.MIN_WINDOW, window.allowed, "one connection always keeps progress")
    }

    @Test
    fun `recovery is slow - a full run of clean chunks wins back exactly one step`() {
        val clock = FakeClock()
        val recovery = 3
        val window = ConnectionWindow(
            max = 8,
            reduceCooldownNanos = 1L,
            recoverySuccesses = recovery,
            clock = clock::invoke,
        )
        window.noteThrottle() // 8 -> 4
        assertEquals(4, window.allowed)

        // Fewer than the threshold: still reduced.
        repeat(recovery - 1) { window.noteSuccess() }
        assertEquals(4, window.allowed, "partial success run must not grow yet")

        // The threshold success wins back a single step (never snaps to full width).
        window.noteSuccess()
        assertEquals(5, window.allowed, "growth is one step per full success run")

        // Another full run grows one more, and so on up to max.
        repeat(recovery) { window.noteSuccess() }
        assertEquals(6, window.allowed)
    }

    @Test
    fun `growth stops at the configured maximum`() {
        val clock = FakeClock()
        val window = ConnectionWindow(max = 4, reduceCooldownNanos = 1L, recoverySuccesses = 1, clock = clock::invoke)
        window.noteThrottle() // 4 -> 2
        assertEquals(2, window.allowed)
        repeat(10) { window.noteSuccess() }
        assertEquals(4, window.allowed, "recovery must cap at max")
    }

    @Test
    fun `a throttle resets the pending recovery progress`() {
        val clock = FakeClock()
        val window = ConnectionWindow(max = 8, reduceCooldownNanos = 1L, recoverySuccesses = 3, clock = clock::invoke)
        window.noteThrottle() // 8 -> 4
        window.noteSuccess()
        window.noteSuccess()
        // Two successes banked; a fresh throttle must clear them so growth restarts from scratch.
        clock.advance(10)
        window.noteThrottle() // 4 -> 2
        window.noteSuccess()
        assertEquals(2, window.allowed, "banked successes must not leak across a throttle")
    }

    @Test
    fun `the gate never lets more requests run than the window allows`() {
        // max = 3: drive 6 workers and assert the observed concurrency peak is bounded by the window.
        val window = ConnectionWindow(max = 3, reduceCooldownNanos = 1L)
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        runBlocking {
            withTimeout(10_000) {
                val jobs = (1..6).map {
                    launch {
                        window.acquire()
                        try {
                            val now = current.incrementAndGet()
                            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                            // Hold the slot briefly so overlapping workers are actually observable.
                            async { }.await()
                        } finally {
                            current.decrementAndGet()
                            window.release()
                        }
                    }
                }
                jobs.forEach { it.join() }
            }
        }
        assertTrue(peak.get() <= 3, "gate let ${peak.get()} run while the window was 3")
    }
}
