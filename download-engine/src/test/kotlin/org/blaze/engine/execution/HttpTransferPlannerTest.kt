package org.blaze.engine.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The planner is pure arithmetic, and its arithmetic is what decides whether a resumed transfer
 * re-reads chunks it already has. A layout that changes between runs silently invalidates the
 * whole sidecar, so these invariants are worth pinning down.
 */
class HttpTransferPlannerTest {

    private val mib = 1024L * 1024L

    @Test
    fun `unknown size produces no work units`() {
        val plan = HttpTransferPlanner.plan(-1L, connections = 4, preferredChunkBytes = 8 * mib)
        assertEquals(0, plan.chunkCount)
    }

    @Test
    fun `a single connection is the whole file`() {
        val plan = HttpTransferPlanner.plan(100 * mib, connections = 1, preferredChunkBytes = 8 * mib)
        assertEquals(1, plan.chunkCount)
        assertEquals(100 * mib, plan.chunkSize)
    }

    @Test
    fun `chunks stay within the configured bounds`() {
        val plan = HttpTransferPlanner.plan(1024L * mib, connections = 8, preferredChunkBytes = 8 * mib)
        assertTrue(plan.chunkSize >= HttpTransferPlanner.MIN_CHUNK, "chunk too small")
        assertTrue(plan.chunkSize <= HttpTransferPlanner.MAX_CHUNK, "chunk too large")
    }

    @Test
    fun `a small file is split enough to keep every connection busy`() {
        val plan = HttpTransferPlanner.plan(16 * mib, connections = 4, preferredChunkBytes = 8 * mib)
        assertTrue(plan.chunkCount >= 4, "expected at least one chunk per connection, got ${plan.chunkCount}")
        assertTrue(plan.chunkCount <= 64, "chunk count exploded to ${plan.chunkCount}")
    }

    @Test
    fun `the unit count stays bounded on enormous archives`() {
        val hundredGiB = 100L * 1024 * mib
        val plan = HttpTransferPlanner.plan(hundredGiB, connections = 16, preferredChunkBytes = mib)
        assertTrue(
            plan.chunkCount <= HttpTransferPlanner.MAX_CHUNKS,
            "resume state would track ${plan.chunkCount} chunks"
        )
    }

    @Test
    fun `chunks tile the file exactly once`() {
        val total = 33L * mib + 12345L
        val plan = HttpTransferPlanner.plan(total, connections = 6, preferredChunkBytes = 4 * mib)

        assertEquals(0L, plan.startOf(0))
        assertEquals(total - 1, plan.endOf(plan.chunkCount - 1, total))

        var covered = 0L
        for (index in 0 until plan.chunkCount) {
            val start = plan.startOf(index)
            val end = plan.endOf(index, total)
            assertTrue(end >= start, "chunk $index is inverted")
            assertTrue(end < total, "chunk $index reads past the end of the file")
            assertEquals(covered, start, "chunk $index does not follow the previous one")
            covered = end + 1
        }
        assertEquals(total, covered, "the plan does not cover the whole file")
    }

    @Test
    fun `a resumed plan reproduces the original layout`() {
        val total = 40L * mib
        val original = HttpTransferPlanner.plan(total, connections = 4, preferredChunkBytes = 8 * mib)
        // The coordinator rebuilds the plan from the persisted chunk size; identical math must
        // produce identical offsets or completed chunks would point at the wrong bytes.
        val resumed = HttpTransferPlanner.Plan(original.chunkSize, original.chunkCount)

        for (index in 0 until original.chunkCount) {
            assertEquals(original.startOf(index), resumed.startOf(index))
            assertEquals(original.endOf(index, total), resumed.endOf(index, total))
        }
    }
}
