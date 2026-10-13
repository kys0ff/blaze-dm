package org.island.shell

import androidx.compose.runtime.MutableState
import androidx.compose.ui.window.WindowState

/**
 * The slice of an app's window lifecycle that [IslandWindow] drives. Blaze's `AppShell` (and any
 * other host's equivalent) implements this shape; the island shell never needs to know what hiding
 * to a tray, closing a modal, or a maximized frame actually means to the app - it just calls back.
 *
 * [windowVisible] and [windowRaiseToken] are exposed as Compose [MutableState]s because the shell
 * reads them inside the composition: the tray (or an external-open effect) toggling visibility
 * recomposes the frame; bumping the raise token re-runs the raise even when the frame was already
 * visible (so a magnet click that arrives while the window sits behind another app still drags it
 * to the front).
 */
interface IslandWindowHost {
    /** The Compose-side window state (size, position, placement). */
    val windowState: WindowState

    /** Whether the frame should currently be mapped. Read reactively. */
    val windowVisible: MutableState<Boolean>

    /** Monotonic counter - bumping it re-runs the raise path in [IslandWindow]. */
    val windowRaiseToken: MutableState<Long>

    /** The frame's close-request callback (quit, or hide-to-tray, per the app's policy). */
    fun onCloseRequest()

    /** The app's minimize path (typically the same hide-to-tray the close-request folds into). */
    fun onMinimize()

    /** Whether the frame currently fills the screen - reflected by the window-system menu's rows. */
    fun isMaximized(): Boolean

    /** Drop the frame back to floating at the size it had before it was maximized. */
    fun restoreWindow()

    /** Fill the screen with the frame, remembering the pre-maximize size for [restoreWindow]. */
    fun maximizeWindow()
}
