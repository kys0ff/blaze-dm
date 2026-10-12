package org.blaze.presentation.screens.main

/**
 * A user interaction with the application shell — the chrome (stripe, status bar, dialogs) that sits
 * around the downloads list. The list's own lifecycle and layout actions stay in
 * [org.blaze.presentation.screens.downloads.DownloadsEvent]; these are only the window-level ones.
 */
sealed interface MainEvent {
    /** Opens the modal About dialog (both the title-bar command and the shell's own request fold here). */
    data object ShowAbout : MainEvent

    /** Closes the About dialog. */
    data object DismissAbout : MainEvent

    /** Expands or collapses the status-bar background-tasks popup. */
    data object ToggleTasksPopup : MainEvent

    /** Collapses the background-tasks popup (its scrim, close button, or a row activation). */
    data object DismissTasksPopup : MainEvent

    /**
     * A stripe entry clicked. Downloads lowers the Extensions window (it is the resting content);
     * Extensions toggles its own panel beside the list. The window's layout stays with its model — the
     * shell only asks it to raise or lower.
     */
    data class SelectToolWindow(val window: MainToolWindowId) : MainEvent

    /**
     * A running task clicked in the popup. The jump is served by the view's
     * [org.blaze.presentation.search.SearchController] (the shared activator the downloads screen
     * registers), so the model turns this into a one-shot [MainEffect.JumpToDownload].
     */
    data class FocusDownload(val id: String) : MainEvent
}
