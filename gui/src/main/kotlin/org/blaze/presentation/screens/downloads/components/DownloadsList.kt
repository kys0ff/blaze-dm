package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.screens.downloads.DownloadCapabilities
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

@Composable
fun DownloadsList(
    downloads: List<Download>,
    selectedId: String?,
    listState: LazyListState,
    onEvent: (DownloadsEvent) -> Unit,
    onRemoveRequested: (Download) -> Unit,
    onPauseRequested: (Download) -> Unit,
    onResumeRequested: (Download) -> Unit,
    onShowDetailsRequested: (String) -> Unit,
    onSelect: (String) -> Unit,
    hideResumeForQueued: Boolean,
    capabilities: DownloadCapabilities = DownloadCapabilities(),
    modifier: Modifier = Modifier
) {
    VerticallyScrollableContainer(listState, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
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
                    setPriority = { priority -> onEvent(DownloadsEvent.SetPriority(id, priority)) },
                )
                DownloadRow(
                    download = download,
                    actions = actions,
                    isSelected = download.id == selectedId,
                    onSelect = { onSelect(download.id) },
                    hideResume = hideResumeForQueued && (download.state == DownloadState.QUEUED),
                    capabilities = capabilities
                )
            }
        }
    }
}
