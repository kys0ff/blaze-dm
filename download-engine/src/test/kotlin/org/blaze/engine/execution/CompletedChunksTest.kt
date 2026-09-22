package org.blaze.engine.execution

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chunk table is the single piece of state every HTTP connection of a segmented download
 * shares, and `java.util.BitSet` is explicitly not thread-safe: a read concurrent with a grow can
 * throw or miss a bit, which is a silently corrupt file. The wrapper is therefore tested directly,
 * with enough chunks that the underlying word array has to grow while threads are hammering it.
 */
class CompletedChunksTest {

    private val chunkCount = 4096
    private val workers = 8

    @Test
    fun `concurrent marking loses and duplicates nothing`() {
        val state = HttpDownloadCoordinator.CompletedChunks(chunkCount)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until workers).map { worker ->
            Thread {
                try {
                    start.await()
                    var chunk = worker
                    while (chunk < chunkCount) {
                        state.markDone(chunk)
                        assertTrue(state.isDone(chunk), "chunk $chunk vanished right after being marked")
                        // The periodic saver and the final sidecar write both clone the table.
                        assertTrue(
                            state.snapshot().get(chunk),
                            "a clone taken while others were writing missed chunk $chunk"
                        )
                        chunk += workers
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(30_000) }

        assertTrue(failures.isEmpty(), "a concurrent access to the chunk table failed: $failures")
        assertEquals(chunkCount, state.cardinality(), "chunks were lost or double counted")
        assertEquals(chunkCount, state.length(), "the table never saw the high chunks")
    }

    @Test
    fun `marking a chunk twice is reported as a duplicate`() {
        val state = HttpDownloadCoordinator.CompletedChunks(64)

        assertTrue(state.markDone(9), "the first mark of a chunk must count as new work finished")
        assertFalse(state.markDone(9), "a re-claimed chunk must not be counted a second time")
        assertEquals(1, state.cardinality())
    }

    @Test
    fun `an empty table says nothing is done`() {
        val state = HttpDownloadCoordinator.CompletedChunks(1024)

        assertFalse(state.isDone(1000))
        assertEquals(0, state.cardinality())
        assertEquals(0, state.length())
    }
}
