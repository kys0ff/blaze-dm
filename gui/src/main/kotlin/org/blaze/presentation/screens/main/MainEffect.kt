package org.blaze.presentation.screens.main

/**
 * One-shot side effects the shell performs outside its state. A dialog open or a popup expand has a
 * visible on/off and so lives in [MainState], never here; the single effect is the imperative "jump to
 * this download", which only the view's [org.blaze.presentation.search.SearchController] — not the
 * model — can carry out, mirroring the very activator Search Everywhere uses.
 */
sealed interface MainEffect {
    /** Route a background-task click to the download, reusing the Search Everywhere activator. */
    data class JumpToDownload(val id: String) : MainEffect
}
