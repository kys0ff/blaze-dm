package org.blaze.presentation.components

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation as FoundationContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import org.jetbrains.jewel.ui.component.ContextMenuRepresentation as JewelContextMenuRepresentation

/**
 * The pure decision behind [CrashSafeContextMenuRepresentation], kept outside composition so it can
 * be unit-tested.
 *
 * Compose Desktop crashes (`IllegalArgumentException: RootNodeOwner is already disposed`) when a
 * popup's scene is torn down *inside* the pointer-event dispatch that requested the teardown: the
 * event handler runs, the dismissed popup's composition is disposed in the same snapshot apply,
 * and the measure-and-layout that closes the dispatch then operates on a dead root. A context menu
 * always closes exactly that way - its item's click handler dismisses the very popup the click is
 * being delivered in.
 *
 * The guard therefore renders the menu from a [shadow] state the delegate may close freely (the
 * popup disappears immediately), but forwards that close to the real [ContextMenuState] only after
 * a frame has passed - i.e. once the dismissing dispatch is long finished.
 */
internal class DismissDeferral {

    /** What the delegate renders and writes: opening is mirrored through, closing is not forwarded. */
    val shadow = ContextMenuState()

    private var seenReal: ContextMenuState.Status? = null

    /**
     * Mirrors the area's current [realStatus] into [shadow] by instance identity, not equality.
     * Identity matters: two right-clicks at the same point produce equal `Open` values, and an
     * equality check would miss the reopen and leave the menu hidden while the area thinks it is
     * open. Called during composition; the guarded write converges after one recomposition.
     */
    fun mirror(realStatus: ContextMenuState.Status) {
        if (seenReal !== realStatus) {
            seenReal = realStatus
            shadow.status = realStatus
        }
    }

    /** True while the delegate has asked to close a menu the area still considers open. */
    fun closeIsPending(realStatus: ContextMenuState.Status): Boolean =
        shadow.status is ContextMenuState.Status.Closed && realStatus is ContextMenuState.Status.Open

    /**
     * Commits the pending close to [state] after the frame wait. A no-op unless the shadow still
     * says closed while [state] still considers the menu open - if the menu was re-opened meanwhile
     * (a new right-click mirrors through first), the shadow no longer says closed and nothing is
     * written.
     */
    fun forwardCloseIfStillPending(state: ContextMenuState) {
        if (closeIsPending(state.status)) state.status = ContextMenuState.Status.Closed
    }
}

/**
 * Jewel's themed context-menu popup, with the dismiss race in [DismissDeferral] neutralized.
 * Provide via `LocalContextMenuRepresentation` (see DownloadsScreen).
 */
@Suppress("DEPRECATION")
internal object CrashSafeContextMenuRepresentation : FoundationContextMenuRepresentation {

    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val guard = remember { DismissDeferral() }

        guard.mirror(state.status)
        // Reading shadow.status inside closeIsPending subscribes the composition to the delegate's
        // close request, so pendingClose flips on the same frame the menu was dismissed.
        val pendingClose = guard.closeIsPending(state.status)

        LaunchedEffect(pendingClose) {
            if (pendingClose) {
                // Outlast the dispatch that closed the menu: disposing inside it is the crash.
                withFrameNanos { }
                guard.forwardCloseIfStillPending(state)
            }
        }

        JewelContextMenuRepresentation.Representation(guard.shadow, items)
    }
}
