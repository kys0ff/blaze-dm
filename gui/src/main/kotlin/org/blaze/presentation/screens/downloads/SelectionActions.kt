package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState

/**
 * Which transport commands a row can actually obey, stated once so the row button, its right-click
 * menu and the bulk cluster never disagree about what a state means.
 *
 * A finished download is deliberately *not* resumable: pressing "resume" on a COMPLETED row would
 * re-fetch a file the user already has, so that path is the explicit re-download entry instead.
 */
fun Download.canPause(): Boolean =
    state == DownloadState.DOWNLOADING || state == DownloadState.SEEDING

/**
 * Whether the row can be started or continued. [queuedBlocked] is the concurrency ceiling already
 * using every slot, which makes resuming a queued row a no-op the user should not be offered.
 */
fun Download.canResume(queuedBlocked: Boolean = false): Boolean = when (state) {
    DownloadState.PAUSED -> true
    DownloadState.QUEUED -> !queuedBlocked
    else -> false
}

/** Still on the list and not already stopped, so abandoning it is a decision worth offering. */
fun Download.canCancel(): Boolean =
    canPause() || state == DownloadState.PAUSED || state == DownloadState.QUEUED

/** Every row can leave the list; a bulk removal never touches files, so it stays always applicable. */
// TODO: actually implement the removal and make use of it
fun Download.canRemove(): Boolean = true

/**
 * What the bulk-action cluster may offer for the current selection: a command is enabled only when at
 * least one selected row would obey it, and it is then applied to exactly those rows. Selecting a
 * finished download therefore cannot light up "Resume", let alone restart it.
 */
data class SelectionActions(
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRemove: Boolean,
) {
    companion object {
        /** Nothing selected - every bulk button is inert. */
        val EMPTY = SelectionActions(
            canPause = false,
            canResume = false,
            canCancel = false,
            canRemove = false,
        )

        fun of(selection: List<Download>, queuedResumeBlocked: Boolean = false): SelectionActions =
            if (selection.isEmpty()) EMPTY else SelectionActions(
                canPause = selection.any { it.canPause() },
                canResume = selection.any { it.canResume(queuedResumeBlocked) },
                canCancel = selection.any { it.canCancel() },
                canRemove = selection.any { it.canRemove() },
            )
    }
}
