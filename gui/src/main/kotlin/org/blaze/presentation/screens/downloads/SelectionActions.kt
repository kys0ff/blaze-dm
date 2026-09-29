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
 * What the bulk-action cluster may offer for the current selection: how many selected rows would obey
 * each command (the `*Count` fields). A command is enabled only when its count is non-zero, and it is
 * then applied to exactly those rows, so the tooltip can honestly read "Pause 3 downloads" and a
 * finished download never lights up "Resume". Selecting rows that cannot act leaves the button inert.
 */
data class SelectionActions(
    val pauseCount: Int,
    val resumeCount: Int,
    val cancelCount: Int,
    val removeCount: Int,
) {
    val canPause: Boolean get() = pauseCount > 0
    val canResume: Boolean get() = resumeCount > 0
    val canCancel: Boolean get() = cancelCount > 0
    val canRemove: Boolean get() = removeCount > 0

    companion object {
        /** Nothing selected - every bulk button is inert. */
        val EMPTY = SelectionActions(
            pauseCount = 0,
            resumeCount = 0,
            cancelCount = 0,
            removeCount = 0,
        )

        fun of(selection: List<Download>, queuedResumeBlocked: Boolean = false): SelectionActions =
            if (selection.isEmpty()) EMPTY else SelectionActions(
                pauseCount = selection.count { it.canPause() },
                resumeCount = selection.count { it.canResume(queuedResumeBlocked) },
                cancelCount = selection.count { it.canCancel() },
                removeCount = selection.count { it.canRemove() },
            )
    }
}
