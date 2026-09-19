package org.blaze.presentation.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import kotlinx.coroutines.launch
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.components.AddDownloadDialog
import org.blaze.presentation.screens.downloads.components.DownloadRow
import org.blaze.presentation.screens.downloads.components.RemoveDownloadDialog
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

class DownloadsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        
        var showAddDialog by remember { mutableStateOf(false) }
        var downloadToRemove by remember { mutableStateOf<Download?>(null) }
        var selectedId by remember { mutableStateOf<String?>(null) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        var defaultDeleteBehavior by remember { mutableStateOf<Boolean?>(null) } 

        val hasActive = state.downloads.any { it.state == DownloadState.DOWNLOADING }
        val hasPaused = state.downloads.any { it.state == DownloadState.PAUSED }
        val hasCompleted = state.downloads.any { it.state == DownloadState.COMPLETED }

        LaunchedEffect(Unit) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    is DownloadsEffect.ShowError -> println("Error: ${effect.message}")
                    is DownloadsEffect.ShowMessage -> println("Message: ${effect.message}")
                }
            }
        }

        if (showAddDialog) {
            AddDownloadDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { url, destination, name ->
                    screenModel.onEvent(DownloadsEvent.AddDownload(url, destination, name))
                },
                onFetchMetadata = { url ->
                    screenModel.fetchMetadata(url)
                }
            )
        }

        downloadToRemove?.let { download ->
            val currentBehavior = defaultDeleteBehavior
            if (currentBehavior != null) {
                screenModel.onEvent(DownloadsEvent.Remove(download.id, currentBehavior))
                downloadToRemove = null
            } else {
                RemoveDownloadDialog(
                    downloadName = download.name,
                    onDismiss = { downloadToRemove = null },
                    onConfirm = { deleteFile, setAsDefault ->
                        if (setAsDefault) {
                            defaultDeleteBehavior = deleteFile
                        }
                        screenModel.onEvent(DownloadsEvent.Remove(download.id, deleteFile))
                        downloadToRemove = null
                    }
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(
                title = "Downloads",
                actions = {
                    ToolbarIconButton(
                        key = AllIconsKeys.General.Add,
                        tooltip = "Add download",
                        onClick = { showAddDialog = true }
                    )
                    ToolbarIconButton(
                        key = AllIconsKeys.Actions.Resume,
                        tooltip = "Resume all",
                        enabled = hasPaused,
                        onClick = { screenModel.onEvent(DownloadsEvent.ResumeAll) }
                    )
                    ToolbarIconButton(
                        key = AllIconsKeys.Actions.Pause,
                        tooltip = "Pause all",
                        enabled = hasActive,
                        onClick = { screenModel.onEvent(DownloadsEvent.PauseAll) }
                    )
                    Divider(
                        Orientation.Vertical,
                        modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
                    )
                    ToolbarIconButton(
                        key = AllIconsKeys.Actions.GC,
                        tooltip = "Clear completed",
                        enabled = hasCompleted,
                        onClick = { screenModel.onEvent(DownloadsEvent.ClearCompleted) }
                    )
                }
            )

            if (state.downloads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No downloads yet",
                            color = JewelTheme.globalColors.text.info
                        )
                        Link(text = "Add a download", onClick = { showAddDialog = true })
                    }
                }
            } else {
                VerticallyScrollableContainer(listState, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(state.filteredDownloads, key = { it.id }) { download ->
                            DownloadRow(
                                download = download,
                                onPause = { screenModel.onEvent(DownloadsEvent.Pause(download.id)) },
                                onResume = { screenModel.onEvent(DownloadsEvent.Resume(download.id)) },
                                onRemove = { downloadToRemove = download },
                                onRetry = { screenModel.onEvent(DownloadsEvent.Retry(download.id)) },
                                onCancel = { screenModel.onEvent(DownloadsEvent.Cancel(download.id)) },
                                isSelected = download.id == selectedId,
                                onSelect = { selectedId = download.id }
                            )
                        }
                    }
                }
            }
        }
    }
}
