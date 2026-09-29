package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The eligibility vocabulary behind the row buttons, the right-click menu and the bulk cluster.
 *
 * Main regression guarded here: a finished download is *not* resumable, so selecting one must never
 * light up a "resume" that would re-fetch a file the user already has.
 */
class SelectionActionsTest {

    private fun download(
        id: String,
        state: DownloadState,
    ) = Download(
        id = id,
        name = "$id.bin",
        url = "https://example.com/$id.bin",
        totalSize = 1_000,
        downloadedSize = if (state == DownloadState.COMPLETED) 1_000 else 500,
        speed = 0,
        state = state,
        addedAt = 0L,
        savePath = "/home/user/Downloads/$id.bin",
    )

    private val completed = download("done", DownloadState.COMPLETED)
    private val seeding = download("seed", DownloadState.SEEDING)
    private val paused = download("halted", DownloadState.PAUSED)
    private val queued = download("waiting", DownloadState.QUEUED)
    private val running = download("moving", DownloadState.DOWNLOADING)
    private val failed = download("broken", DownloadState.FAILED)

    // ── per-row eligibility ──────────────────────────────────────────────────

    @Test
    fun `a finished row offers no transport command at all`() {
        assertFalse(completed.canPause())
        assertFalse(completed.canResume())
        assertFalse(completed.canCancel())

        // Seeding is still work the app is doing, so it can be stopped, just not "resumed".
        assertTrue(seeding.canPause())
        assertFalse(seeding.canResume())
    }

    @Test
    fun `a parked or waiting row resumes, and a failed one does not`() {
        assertTrue(paused.canResume())
        assertTrue(queued.canResume())
        assertFalse(failed.canResume())
        assertFalse(running.canResume())
    }

    @Test
    fun `a queued row is not resumable while every slot is taken`() {
        assertFalse(queued.canResume(queuedBlocked = true))
        // A row already parked still has a pause to lift.
        assertTrue(paused.canResume(queuedBlocked = true))
    }

    @Test
    fun `only a transfer that is in flight or parked can be cancelled`() {
        assertTrue(running.canCancel())
        assertTrue(paused.canCancel())
        assertTrue(queued.canCancel())
        assertFalse(completed.canCancel())
        assertFalse(failed.canCancel())
    }

    // ── what a selection offers ──────────────────────────────────────────────

    @Test
    fun `a selection of finished rows offers only what a seed can still do`() {
        val actions = SelectionActions.of(listOf(completed, seeding))

        assertFalse(actions.canResume)
        // Cancel follows the seeding row, which is still an active transfer.
        assertTrue(actions.canCancel)
        assertTrue(actions.canPause)
        assertTrue(actions.canRemove)
    }

    @Test
    fun `a selection of nothing but finished files offers no transport command`() {
        val actions = SelectionActions.of(listOf(completed, completed.copy(id = "done2")))

        assertFalse(actions.canPause)
        assertFalse(actions.canResume)
        assertFalse(actions.canCancel)
        assertTrue(actions.canRemove)
    }

    @Test
    fun `one resumable row is enough to enable the bulk resume`() {
        val actions = SelectionActions.of(listOf(completed, paused))

        assertTrue(actions.canResume)
        assertFalse(actions.canPause)
    }

    @Test
    fun `queued-only selection loses resume once the slots are full`() {
        val actions = SelectionActions.of(listOf(queued, completed), queuedResumeBlocked = true)

        assertFalse(actions.canResume)
        assertTrue(actions.canCancel)
    }

    @Test
    fun `an empty selection offers nothing`() {
        val actions = SelectionActions.of(emptyList())

        assertFalse(actions.canPause)
        assertFalse(actions.canResume)
        assertFalse(actions.canCancel)
        assertFalse(actions.canRemove)
    }
}
