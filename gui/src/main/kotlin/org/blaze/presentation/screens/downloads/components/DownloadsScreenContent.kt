package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsState
import java.io.File

@Composable
fun DownloadsScreenContent(
    state: DownloadsState,
    onEvent: (DownloadsEvent) -> Unit,
    onFetchMetadata: suspend (String) -> DownloadMetadata?,
    onResolveDestinationPath: suspend (String, String, String?) -> String,
    fileConflictBehavior: FileConflictBehavior,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var downloadToRemove by remember { mutableStateOf<Download?>(null) }
    var conflictData by remember { mutableStateOf<ConflictData?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var defaultDeleteBehavior by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    val hasActive = remember(state.downloads) { state.downloads.any { it.state == DownloadState.DOWNLOADING } }
    val hasPaused = remember(state.downloads) { state.downloads.any { it.state == DownloadState.PAUSED } }
    val hasCompleted = remember(state.downloads) { state.downloads.any { it.state == DownloadState.COMPLETED } }

    val handleRemoveRequest: (Download) -> Unit = { download ->
        val currentBehavior = defaultDeleteBehavior
        if (currentBehavior != null) {
            onEvent(DownloadsEvent.Remove(download.id, currentBehavior))
        } else {
            downloadToRemove = download
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url, destination, name, fileIndices, totalSize, files ->
                if (fileConflictBehavior == FileConflictBehavior.ASK) {
                    scope.launch {
                        val fullPathStr = onResolveDestinationPath(url, destination, name)
                        val file = File(fullPathStr)
                        if (file.exists()) {
                            conflictData = ConflictData(url, destination, name, file.name, fullPathStr, fileIndices)
                        } else {
                            onEvent(DownloadsEvent.AddDownload(url, destination, name, fileIndices, totalSize, files))
                        }
                    }
                } else {
                    onEvent(DownloadsEvent.AddDownload(url, destination, name, fileIndices, totalSize, files))
                }
            },
            onFetchMetadata = onFetchMetadata
        )
    }

    conflictData?.let { data ->
        FileConflictDialog(
            fileName = data.fileName,
            onDismiss = { conflictData = null },
            onConfirm = { choice ->
                when (choice) {
                    FileConflictBehavior.OVERWRITE -> {
                        scope.launch {
                            val f = File(data.fullPathStr)
                            f.delete()
                            val partFile = File(data.fullPathStr + ".part")
                            partFile.delete()
                            onEvent(DownloadsEvent.AddDownload(data.url, data.savePath, data.name, data.fileIndices))
                        }
                    }
                    FileConflictBehavior.RENAME -> {
                        onEvent(DownloadsEvent.AddDownload(data.url, data.savePath, data.name, data.fileIndices))
                    }
                    else -> {} // SKIP: do nothing
                }
                conflictData = null
            }
        )
    }

    downloadToRemove?.let { download ->
        RemoveDownloadDialog(
            downloadName = download.name,
            onDismiss = { downloadToRemove = null },
            onConfirm = { deleteFile, setAsDefault ->
                if (setAsDefault) {
                    defaultDeleteBehavior = deleteFile
                }
                onEvent(DownloadsEvent.Remove(download.id, deleteFile))
                downloadToRemove = null
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        DownloadsToolbar(
            hasActiveDownloads = hasActive,
            hasPausedDownloads = hasPaused,
            hasCompletedDownloads = hasCompleted,
            onEvent = onEvent,
            onAddDownload = { showAddDialog = true }
        )

        if (state.downloads.isEmpty()) {
            DownloadsEmptyState(
                onAddDownload = { showAddDialog = true }
            )
        } else {
            val activeCount = remember(state.downloads) {
                state.downloads.count { it.state == DownloadState.DOWNLOADING }
            }
            val hideResumeForQueued = activeCount >= state.maxConcurrentDownloads

            DownloadsList(
                downloads = state.filteredDownloads,
                selectedId = selectedId,
                listState = listState,
                onEvent = onEvent,
                onRemoveRequested = handleRemoveRequest,
                onSelect = { id -> selectedId = id },
                hideResumeForQueued = hideResumeForQueued
            )
        }
    }
}
