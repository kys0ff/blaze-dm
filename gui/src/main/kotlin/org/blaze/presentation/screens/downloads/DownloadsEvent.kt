package org.blaze.presentation.screens.downloads

import org.blaze.domain.repository.DownloadFile
import org.blaze.download.api.DownloadPriority

sealed interface DownloadsEvent {
    data class SearchChanged(val query: String) : DownloadsEvent

    /** Picks the sort column; selecting the already-active column flips the direction. */
    data class SortBy(val field: DownloadSortField) : DownloadsEvent

    /** Narrows the list to a single state (or clears the narrowing with [DownloadFilter.ALL]). */
    data class FilterBy(val filter: DownloadFilter) : DownloadsEvent

    /** Sets a table column's width in dp ([columnId] is a `DownloadsColumn.id`); persisted. */
    data class ResizeColumn(val columnId: String, val widthDp: Float) : DownloadsEvent

    /**
     * Shows or hides one table column (header Table options > Columns). [columnId] is a
     * `DownloadsColumn.id`; the Name column is never hidden, so a row always stays identifiable.
     */
    data class ToggleColumn(val columnId: String, val visible: Boolean) : DownloadsEvent

    /** Gives every column back its default width (header Table options); persisted. */
    data object ResetColumnWidths : DownloadsEvent

    /** Records the list|details split fraction after a divider drag; persisted. */
    data class SetSplitFraction(val fraction: Float) : DownloadsEvent

    /** Shows or hides the Details tool window; persisted. */
    data class ToggleDetails(val visible: Boolean) : DownloadsEvent

    /** Turns the pause-progress confirmation on/off (the "do not ask again" checkbox); persisted. */
    data class SetWarnBeforeDiscardingProgress(val enabled: Boolean) : DownloadsEvent

    /** Picks the Details pane's view mode (docked / floated); persisted. */
    data class SetDetailsViewMode(val mode: DetailsViewMode) : DownloadsEvent

    /** Docks the Details pane to a new edge/corner; persisted. */
    data class SetDetailsMoveTo(val position: DetailsMoveTo) : DownloadsEvent

    /** Groups the Details pane's tabs into one continuous view, or splits them back out; persisted. */
    data class ToggleGroupDetailsTabs(val enabled: Boolean) : DownloadsEvent

    /** Applies a size/side preset from the Details pane's Resize submenu; persisted. */
    data class ResizeDetails(val preset: DetailsResize) : DownloadsEvent
    data class AddDownload(
        val url: String,
        val savePath: String,
        val name: String? = null,
        val fileIndices: List<Int>? = null,
        val totalSize: Long? = null,
        val files: List<DownloadFile>? = null,
        val scheduledAt: Long? = null,
        val priority: DownloadPriority? = null,
    ) : DownloadsEvent
    data class Pause(val id: String) : DownloadsEvent
    data class Resume(val id: String) : DownloadsEvent
    data class Remove(val id: String, val deleteFile: Boolean) : DownloadsEvent
    data class Retry(val id: String) : DownloadsEvent
    data class Cancel(val id: String) : DownloadsEvent
    data class SetPriority(val id: String, val priority: DownloadPriority) : DownloadsEvent
    data object PauseAll : DownloadsEvent
    data object ResumeAll : DownloadsEvent
    data object ClearCompleted : DownloadsEvent

    // ── Row selection (multi-select) ──────────────────────────────────────────────
    /** Pointer click on a row: [additive] = Ctrl/Cmd toggle, [range] = Shift contiguous span. */
    data class SelectRow(val id: String, val additive: Boolean = false, val range: Boolean = false) : DownloadsEvent
    /** Select every visible row (Ctrl/Cmd+A, header or context-menu "Select All"). */
    data object SelectAllRows : DownloadsEvent
    /** Drop the whole selection (Escape, or clicking empty list space). */
    data object ClearRowSelection : DownloadsEvent
    /** Keyboard navigation: move by [delta] rows, extending the span when [extend] (Shift held). */
    data class MoveSelection(val delta: Int, val extend: Boolean = false) : DownloadsEvent

    /** Engine-wide "Snail Mode" crawl toggle; [enabled] is the new desired state. */
    data class ToggleSnailMode(val enabled: Boolean) : DownloadsEvent

    // Desktop integration actions surfaced from the row's right-click menu / double-click.
    data class OpenFile(val id: String) : DownloadsEvent
    data class ShowInFolder(val id: String) : DownloadsEvent
    data class OpenSourceLink(val id: String) : DownloadsEvent
    data class CopyDownloadLink(val id: String) : DownloadsEvent
    data class CopyFileLocation(val id: String) : DownloadsEvent
}
