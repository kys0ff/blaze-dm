package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.screens.downloads.DownloadCapabilities
import org.blaze.presentation.screens.downloads.DownloadSortField
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The downloads table: a sticky [DownloadsTableHeader] (select-all + sortable/resizable columns) over
 * a scrollable [LazyColumn] of [DownloadRow]s. Header and rows read the same [columnWidths] so the
 * columns line up; sort is driven from the header, not the old chips.
 */
@Composable
fun DownloadsList(
    downloads: List<Download>,
    selectedIds: Set<String>,
    listState: LazyListState,
    columnWidths: Map<String, Float>,
    sortField: DownloadSortField,
    sortAscending: Boolean,
    onEvent: (DownloadsEvent) -> Unit,
    onRemoveRequested: (Download) -> Unit,
    onPauseRequested: (Download) -> Unit,
    onResumeRequested: (Download) -> Unit,
    onShowDetailsRequested: (String) -> Unit,
    onSelect: (id: String, additive: Boolean, range: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    hideResumeForQueued: Boolean,
    capabilities: DownloadCapabilities = DownloadCapabilities(),
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        DownloadsTableHeader(
            columnWidths = columnWidths,
            sortField = sortField,
            sortAscending = sortAscending,
            onSelectAll = onSelectAll,
            onSort = { field -> onEvent(DownloadsEvent.SortBy(field)) },
            onResize = { columnId, widthDp -> onEvent(DownloadsEvent.ResizeColumn(columnId, widthDp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        VerticallyScrollableContainer(listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(downloads, key = { it.id }) { download ->
                    val id = download.id
                    val actions = DownloadRowActions(
                        // Pause and resume go through the screen, which may interpose a warning when the
                        // source cannot survive the transition; everything else is a plain command.
                        pause = { onPauseRequested(download) },
                        resume = { onResumeRequested(download) },
                        retry = { onEvent(DownloadsEvent.Retry(id)) },
                        cancel = { onEvent(DownloadsEvent.Cancel(id)) },
                        remove = { onRemoveRequested(download) },
                        // A gone file leaves nothing to delete, so clearing skips the confirmation dialog
                        // and just drops the row; re-downloading re-queues the very same source.
                        reDownload = { onEvent(DownloadsEvent.Retry(id)) },
                        clear = { onEvent(DownloadsEvent.Remove(id, deleteFile = false)) },
                        openFile = { onEvent(DownloadsEvent.OpenFile(id)) },
                        showInFolder = { onEvent(DownloadsEvent.ShowInFolder(id)) },
                        openSourceLink = { onEvent(DownloadsEvent.OpenSourceLink(id)) },
                        copyLink = { onEvent(DownloadsEvent.CopyDownloadLink(id)) },
                        copyFileLocation = { onEvent(DownloadsEvent.CopyFileLocation(id)) },
                        showDetails = { onShowDetailsRequested(id) },
                        selectAll = onSelectAll,
                        setPriority = { priority -> onEvent(DownloadsEvent.SetPriority(id, priority)) },
                    )
                    DownloadRow(
                        download = download,
                        actions = actions,
                        columnWidths = columnWidths,
                        isSelected = download.id in selectedIds,
                        onSelect = { additive, range -> onSelect(id, additive, range) },
                        hideResume = hideResumeForQueued && (download.state == DownloadState.QUEUED),
                        capabilities = capabilities,
                    )
                }
            }
        }
    }
}
