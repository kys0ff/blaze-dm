package org.island.foundation.window

import java.awt.Frame
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one fact the main frame consults before folding an ICONIFIED into hide-to-tray: whether a
 * dialog is composed right now. A dialog caption minimize - direct or propagated through a
 * window manager's transient group - is the only way the frame can iconify while a dialog is open
 * (the modal blocks the app's own title-bar buttons), so presence alone decides between "undo the
 * minimize" and "hide to tray". Tracking must therefore be exact on both ends: register when the
 * dialog comes up, unregister when it leaves.
 */
class DialogChromeGuardTest {

    /** A peerless window stands in for a dialog; null on headless runs, where tests skip. */
    private fun dialogStandIn(): Frame? = runCatching { Frame() }.getOrNull()

    @BeforeTest
    fun resetGuard() {
        DialogChromeGuard.reset()
    }

    @Test
    fun `no dialog means a frame iconify is a real minimize`() {
        assertFalse(DialogChromeGuard.hasOpenDialog())
    }

    @Test
    fun `a composed dialog suppresses the frame minimize fold`() {
        val dialog = dialogStandIn() ?: return
        DialogChromeGuard.track(dialog)

        assertTrue(DialogChromeGuard.hasOpenDialog())
    }

    @Test
    fun `a closed dialog stops suppressing`() {
        val dialog = dialogStandIn() ?: return
        DialogChromeGuard.track(dialog)
        DialogChromeGuard.untrack(dialog)

        assertFalse(DialogChromeGuard.hasOpenDialog())
    }

    @Test
    fun `several dialogs keep the guard until the last closes`() {
        val first = dialogStandIn() ?: return
        val second = dialogStandIn() ?: return
        DialogChromeGuard.track(first)
        DialogChromeGuard.track(second)
        DialogChromeGuard.untrack(first)

        assertTrue(DialogChromeGuard.hasOpenDialog())

        DialogChromeGuard.untrack(second)
        assertFalse(DialogChromeGuard.hasOpenDialog())
    }
}
