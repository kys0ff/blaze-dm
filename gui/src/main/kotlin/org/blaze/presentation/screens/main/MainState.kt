package org.blaze.presentation.screens.main

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState

/**
 * A tool window the left stripe can raise over the downloads list. [stripeId] is the stripe's stable
 * key, matching the [org.blaze.presentation.components.SidebarItem.id] the shell renders, so the
 * selected entry and the panel it raises are always the same window.
 */
enum class MainToolWindowId(val stripeId: String) {
    DOWNLOADS("downloads"),
    EXTENSIONS("extensions"),
}

/**
 * The application shell's own transient UI state: the modal About dialog, the expandable background-
 * tasks popup, the tool window the stripe currently marks, and the live transfers the status bar
 * summarises. Everything here is per-window and ephemeral — the downloads list, the Details pane and
 * each dockable window's layout stay with the models that own them, so this never duplicates them.
 */
data class MainState(
    val aboutVisible: Boolean = false,
    val tasksPopupExpanded: Boolean = false,
    /** The stripe entry currently highlighted; mirrors whether the Extensions window is raised. */
    val activeToolWindow: MainToolWindowId = MainToolWindowId.DOWNLOADS,
    /** Downloads actually moving (downloading or queued), counted and listed in the status bar. */
    val running: List<Download> = emptyList(),
)

/**
 * Live transfers surfaced in the status bar: QUEUED counts too so the popup shows the whole pending
 * set, while a row's own pause button is only offered while it is actually moving. Kept pure so the
 * projection can be unit-tested apart from the flow that drives it.
 */
fun runningDownloads(downloads: List<Download>): List<Download> =
    downloads.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }

/**
 * The stripe marks Extensions while its panel is raised; Downloads is the resting central content, so
 * it is selected whenever the Extensions window is down. Pure, so the shell's selection rule is testable.
 */
fun activeToolWindowFor(extensionsVisible: Boolean): MainToolWindowId =
    if (extensionsVisible) MainToolWindowId.EXTENSIONS else MainToolWindowId.DOWNLOADS

/**
 * Reduces the shell's *own* UI state from a [MainEvent]. Only the events the shell can serve itself
 * change anything here: the About dialog and the background-tasks popup. A tool-window selection or a
 * task jump delegate to another model (or leave as an effect), so they fold the state unchanged — the
 * model applies them separately. Pure and total, so the shell's reduce is unit-testable without a
 * coroutine scope or a live download stream.
 */
fun reduceMainUi(current: MainState, event: MainEvent): MainState = when (event) {
    MainEvent.ShowAbout -> current.copy(aboutVisible = true)
    MainEvent.DismissAbout -> current.copy(aboutVisible = false)
    MainEvent.ToggleTasksPopup -> current.copy(tasksPopupExpanded = !current.tasksPopupExpanded)
    MainEvent.DismissTasksPopup -> current.copy(tasksPopupExpanded = false)
    is MainEvent.SelectToolWindow, is MainEvent.FocusDownload -> current
}
