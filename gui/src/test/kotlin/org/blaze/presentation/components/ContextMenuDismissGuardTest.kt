package org.blaze.presentation.components

import androidx.compose.foundation.ContextMenuState
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The context-menu dismiss guard's state machine ([DismissDeferral]). Its whole job is a race:
 * a close requested by the menu must stay on the shadow until the pointer dispatch that asked for
 * it is over, while any newer open must win immediately. These are exactly those transitions.
 */
class ContextMenuDismissGuardTest {

    private fun openAt(x: Int) = ContextMenuState.Status.Open(Rect(x.toFloat(), 0f, x + 1f, 1f))

    @Test
    fun `an open request mirrors through to the shadow immediately`() {
        val guard = DismissDeferral()
        val opened = openAt(10)
        guard.mirror(opened)
        assertEquals(opened, guard.shadow.status)
        assertFalse(guard.closeIsPending(opened), "an open menu has nothing pending")
    }

    @Test
    fun `a close stays on the shadow until it is forwarded to a still-open state`() {
        val guard = DismissDeferral()
        val real = ContextMenuState().apply { status = openAt(0) }
        guard.mirror(real.status)

        // The delegate dismisses itself inside the click dispatch: only the shadow flips.
        guard.shadow.status = ContextMenuState.Status.Closed
        assertTrue(guard.closeIsPending(real.status))

        guard.forwardCloseIfStillPending(real)
        assertEquals(ContextMenuState.Status.Closed, real.status)
        assertEquals(ContextMenuState.Status.Closed, guard.shadow.status)
    }

    @Test
    fun `forwarding is a no-op once the real state already says closed`() {
        val guard = DismissDeferral()
        val real = ContextMenuState().apply { status = openAt(0) }
        guard.mirror(real.status)
        real.status = ContextMenuState.Status.Closed
        guard.mirror(real.status) // composition observes it; shadow follows
        guard.shadow.status = ContextMenuState.Status.Closed

        assertFalse(guard.closeIsPending(real.status), "nothing is pending for a menu that is gone")
        guard.forwardCloseIfStillPending(real)
        assertEquals(ContextMenuState.Status.Closed, real.status)
    }

    @Test
    fun `a reopen during the frame wait cancels the stale close - even at an equal position`() {
        val guard = DismissDeferral()
        val real = ContextMenuState().apply { status = openAt(42) }
        guard.mirror(real.status)
        guard.shadow.status = ContextMenuState.Status.Closed // first menu dismissed

        // The user right-clicks again at the very same spot: an equal but distinct Open instance,
        // which is why the mirror compares by identity, not equality.
        val reopened = openAt(42)
        assertEquals(real.status, reopened, "the two opens must be equal for this test to bite")
        real.status = reopened
        guard.mirror(reopened)

        assertFalse(guard.closeIsPending(reopened), "the shadow followed the reopen")
        guard.forwardCloseIfStillPending(real)
        assertEquals(reopened, real.status, "the stale close must not kill the new menu")
    }

    @Test
    fun `forwarding after a reopen that skipped composition still cannot close the new menu`() {
        // Defensive second layer: even if the frame-wait coroutine runs while the shadow says
        // Closed and the real state holds a fresh open, the commit only fires when the pending
        // condition is *still* true - a mirrored reopen cleared it.
        val guard = DismissDeferral()
        val real = ContextMenuState().apply { status = openAt(1) }
        guard.mirror(real.status)
        guard.shadow.status = ContextMenuState.Status.Closed

        val freshOpen = openAt(2)
        real.status = freshOpen
        guard.mirror(freshOpen) // shadow back to Open
        guard.shadow.status = ContextMenuState.Status.Closed // …then this newer close request

        guard.forwardCloseIfStillPending(real)
        assertEquals(ContextMenuState.Status.Closed, real.status, "a genuinely pending close commits")
    }
}
