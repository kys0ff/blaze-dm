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
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

@Composable
fun DownloadsList(
    downloads: List<Download>,
    selectedId: String?,
    listState: LazyListState,
    onEvent: (DownloadsEvent) -> Unit,
    onRemoveRequested: (Download) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    VerticallyScrollableContainer(listState, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(downloads, key = { it.id }) { download ->
                DownloadRow(
                    download = download,
                    onPause = { onEvent(DownloadsEvent.Pause(download.id)) },
                    onResume = { onEvent(DownloadsEvent.Resume(download.id)) },
                    onRemove = { onRemoveRequested(download) },
                    onRetry = { onEvent(DownloadsEvent.Retry(download.id)) },
                    onCancel = { onEvent(DownloadsEvent.Cancel(download.id)) },
                    isSelected = download.id == selectedId,
                    onSelect = { onSelect(download.id) }
                )
            }
        }
    }
}
