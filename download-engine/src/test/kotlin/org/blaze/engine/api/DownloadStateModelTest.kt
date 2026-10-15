package org.blaze.engine.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the unified engine state model that every protocol is mapped onto.
 *
 * The new lifecycle phases ([Probing], [DownloadState.Merging]) and the reserved ones ([Verifying],
 * [DownloadState.Completing]) must survive the persisted `state` string round-trip (`toString` /
 * [DownloadState.fromString]) and report [DownloadState.isActive] honestly, because that string is what
 * `downloads.json` stores and what a restart reads back.
 */
class DownloadStateModelTest {

    @Test
    fun `the new and reserved phases round-trip through the persisted name`() {
        for (state in listOf(DownloadState.Probing, DownloadState.Merging, DownloadState.Completing, DownloadState.Verifying)) {
            val stored = DownloadState.toString(state)
            assertEquals(state::class.simpleName!!.uppercase(), stored, "the stored name is the upper-cased type")
            assertEquals(state, DownloadState.fromString(stored), "$state must decode back to itself")
        }
    }

    @Test
    fun `an unrecognized stored name degrades to Queued rather than throwing`() {
        // Old readers must not crash on a state they have never seen: this is what keeps the persisted
        // format forward-compatible when a newer engine writes a state an older build does not know.
        assertEquals(DownloadState.Queued, DownloadState.fromString("PROBING_BOGUS_VALUE"))
    }

    @Test
    fun `the phases that consume work are active while the resting ones are not`() {
        // Probing (a probe really runs) and Merging (assembly really happens) are active so the
        // scheduler counts them and a client keeps polling; Completing is never emitted and folds into
        // Completed, so it is deliberately not active.
        assertTrue(DownloadState.Probing.isActive)
        assertTrue(DownloadState.Merging.isActive)
        assertTrue(DownloadState.Pausing.isActive)
        assertTrue(DownloadState.Resuming.isActive)
        assertFalse(DownloadState.Completing.isActive)
        assertFalse(DownloadState.Paused.isActive)
        assertFalse(DownloadState.Completed.isActive)
    }
}
