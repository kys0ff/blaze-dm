package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState

/**
 * Which desktop integrations the host can actually perform. Drives whether the download row's
 * context menu shows (or grays out) Open / Show in folder / Open source link, so the UI never
 * offers an action the current platform can't carry out.
 */
data class DownloadCapabilities(
    val canOpenFiles: Boolean = false,
    val canRevealInFolder: Boolean = false,
    val canBrowseLinks: Boolean = false,
    val canCopy: Boolean = false
)

/** The column the downloads list is ordered by. Drives the table header's sort indicator. */
enum class DownloadSortField { NAME, SIZE, SPEED, STATE, DATE_ADDED, PROGRESS, ETA }

/**
 * Rehydrates a persisted sort field name, falling back to the default for an unknown/hand-edited
 * value so a stale `app.json` can never leave the list unorderable.
 */
fun downloadSortFieldFromName(name: String): DownloadSortField =
    DownloadSortField.entries.firstOrNull { it.name == name } ?: DownloadSortField.DATE_ADDED

/**
 * A single-select state filter for the list. [matches] decides which rows survive the active filter,
 * so the predicate lives next to the value it describes and the screen model stays free of `when`s.
 */
enum class DownloadFilter {
    ALL,
    ACTIVE,
    QUEUED,
    PAUSED,
    COMPLETED,
    FAILED,
    SEEDING;

    fun matches(state: DownloadState): Boolean = when (this) {
        ALL -> true
        ACTIVE -> state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
        QUEUED -> state == DownloadState.QUEUED
        PAUSED -> state == DownloadState.PAUSED
        COMPLETED -> state == DownloadState.COMPLETED
        FAILED -> state == DownloadState.FAILED
        SEEDING -> state == DownloadState.SEEDING
    }
}

data class DownloadsState(
    val downloads: List<Download> = emptyList(),
    val filteredDownloads: List<Download> = emptyList(),
    val searchQuery: String = "",
    val sortField: DownloadSortField = DownloadSortField.DATE_ADDED,
    val sortAscending: Boolean = false,
    val filter: DownloadFilter = DownloadFilter.ALL,
    /** Live per-state counts, used to label the filter chips and the status-bar throughput. */
    val counts: DownloadCounts = DownloadCounts(),
    /**
     * The rows currently selected (multi-select). Owned by the screen model's [Selection] so the
     * status bar can show "N selected" and the toolbar bulk actions can act on it; already pruned to
     * rows that still exist in the list.
     */
    val selectedIds: Set<String> = emptySet(),
    val maxConcurrentDownloads: Int = 0,
    /** Engine-wide "Snail Mode" crawl state, mirrored from the download settings for the toolbar/status bar. */
    val snailModeEnabled: Boolean = false,
    val snailSpeedKbps: Long = 64,
    val capabilities: DownloadCapabilities = DownloadCapabilities(),

    // ── Downloads-table layout, seeded from and persisted back to [org.blaze.data.AppSettings] ──
    // The Details pane's own docked layout (visibility, view mode, split, floating) moved to
    // [DetailsPanelScreenModel]; only the table's column concerns remain here.
    /** Chosen column widths in dp keyed by [org.blaze.presentation.components.DownloadsColumn.id]. */
    val columnWidths: Map<String, Float> = emptyMap(),
    /** Ids of the columns hidden through the header's Table options menu (see [columnWidths]). */
    val hiddenColumns: Set<String> = emptySet(),
    /** Whether pausing a progress-losing download asks first (persisted "do not ask again"). */
    val warnBeforeDiscardingProgress: Boolean = true,
)

/**
 * A snapshot of how many downloads sit in each state, computed once per list update so the filter
 * chips and the status bar read counts rather than re-scanning the list in composition.
 */
data class DownloadCounts(
    val downloading: Int = 0,
    val queued: Int = 0,
    val paused: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val seeding: Int = 0,
) {
    /** Every download the list holds, used to label the "All" filter chip. */
    val total: Int get() = downloading + queued + paused + completed + failed + seeding
}

/** Aggregate bytes-per-second across every actively transferring download. */
fun List<Download>.aggregateSpeed(): Long =
    sumOf { if (it.state == DownloadState.DOWNLOADING || it.state == DownloadState.SEEDING) it.speed else 0L }

fun List<Download>.toCounts(): DownloadCounts = DownloadCounts(
    downloading = count { it.state == DownloadState.DOWNLOADING },
    queued = count { it.state == DownloadState.QUEUED },
    paused = count { it.state == DownloadState.PAUSED },
    completed = count { it.state == DownloadState.COMPLETED },
    failed = count { it.state == DownloadState.FAILED },
    seeding = count { it.state == DownloadState.SEEDING },
)

/**
 * Applies the active [filter] and [sortField]/[sortAscending] to [downloads]. Search filtering has
 * already been applied upstream, so this only narrows by state and orders.
 */
fun List<Download>.filterAndSort(filter: DownloadFilter, field: DownloadSortField, ascending: Boolean): List<Download> {
    val narrowed = if (filter == DownloadFilter.ALL) this else filter { filter.matches(it.state) }
    val comparator = comparatorFor(field)
    val sorted = if (ascending) narrowed.sortedWith(comparator) else narrowed.sortedWith(comparator.reversed())
    return sorted
}

private fun comparatorFor(field: DownloadSortField): Comparator<Download> = when (field) {
    DownloadSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    DownloadSortField.SIZE -> compareBy { it.totalSize ?: it.downloadedSize }
    DownloadSortField.SPEED -> compareBy { it.speed }
    // Order by how advanced the lifecycle is, so grouping by status reads top-to-bottom.
    DownloadSortField.STATE -> compareBy { it.state.ordinal }
    DownloadSortField.DATE_ADDED -> compareBy { it.addedAt }
    DownloadSortField.PROGRESS -> compareBy { it.progress }
    // Rows with no live estimate sort together (null), then by whichever ETA is nearer.
    DownloadSortField.ETA -> compareBy<Download> { it.eta == null }.thenBy { it.eta ?: 0L }
}
