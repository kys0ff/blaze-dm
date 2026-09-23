package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.blaze.domain.repository.PortableInfo
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.blazeStrings
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.osintegration.displaySource
import org.blaze.presentation.components.notifications.NotificationType
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsState
import org.blaze.presentation.screens.downloads.findDuplicateOf
import org.blaze.presentation.screens.downloads.pauseLosesProgress
import org.blaze.presentation.screens.downloads.percentDone
import org.blaze.presentation.screens.downloads.resumeStartsOver
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import java.io.File
import java.nio.file.Path

@Composable
fun DownloadsScreenContent(
    state: DownloadsState,
    onEvent: (DownloadsEvent) -> Unit,
    onFetchMetadata: suspend (String) -> DownloadMetadata?,
    onResolveDestinationPath: suspend (String, String, String?) -> String,
    onDetectPortable: suspend (String) -> PortableInfo?,
    onImportPortable: suspend (String, String) -> Boolean,
    onNotify: (message: String, type: NotificationType) -> Unit,
    fileConflictBehavior: FileConflictBehavior,
    externalRequests: List<ExternalOpenRequest> = emptyList(),
    onExternalShown: (ExternalOpenRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = blazeStrings
    var showAddDialog by remember { mutableStateOf(false) }
    // Non-null when the dialog was opened by an external (OS) request rather than by the user;
    // the dialog pre-fills and auto-fetches this source, and manual "+" opens clear it.
    var addDialogSource by remember { mutableStateOf<String?>(null) }
    var downloadToRemove by remember { mutableStateOf<Download?>(null) }
    // Set when pausing would cost the download its progress, so the user confirms the loss first.
    var downloadToPause by remember { mutableStateOf<Download?>(null) }
    var detailsId by remember { mutableStateOf<String?>(null) }
    var conflictData by remember { mutableStateOf<ConflictData?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var defaultDeleteBehavior by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    // Surface queued external opens (torrent files / magnets from the OS) one at a time through
    // the *normal* add-download dialog. The request is acked as it is shown: it has then reached
    // the user's workflow, and dismissing the dialog is the user's choice - it must not
    // resurrect the dialog. Acking shrinks the queue, which re-runs this effect for the next one.
    LaunchedEffect(externalRequests, showAddDialog) {
        if (!showAddDialog && externalRequests.isNotEmpty()) {
            val request = externalRequests.first()
            addDialogSource = request.displaySource
            onExternalShown(request)
            showAddDialog = true
        }
    }

    // ── Portable-import flow: pick the moved artifact, preview it, choose a destination, resume. ──
    var showArtifactPicker by remember { mutableStateOf(false) }
    var showDestPicker by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Pair<String, PortableInfo>?>(null) }
    var importDestination by remember { mutableStateOf("") }

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

    // Pausing is the irreversible one of the transport controls: when the server proved it cannot
    // continue a ranged read, the bytes on disk are discarded, so the row asks first.
    val handlePauseRequest: (Download) -> Unit = { download ->
        if (download.pauseLosesProgress()) downloadToPause = download
        else onEvent(DownloadsEvent.Pause(download.id))
    }

    // Resuming stays unconfirmed - there is nothing left to lose by pressing it - but the cost of
    // the restart is named, so a user who expected to continue at 60% is not surprised by 0%.
    val handleResumeRequest: (Download) -> Unit = { download ->
        if (download.resumeStartsOver()) {
            onNotify(strings.downloads.advisories.resumeStartsOver(download.name), NotificationType.WARNING)
        }
        onEvent(DownloadsEvent.Resume(download.id))
    }

    val handleEvent: (DownloadsEvent) -> Unit = { event ->
        if (event == DownloadsEvent.PauseAll) {
            val costly = state.downloads.count { it.pauseLosesProgress() }
            if (costly > 0) {
                onNotify(strings.downloads.advisories.pauseAllCosts(costly), NotificationType.WARNING)
            }
        }
        onEvent(event)
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            initialSource = addDialogSource,
            onAdd = { url, destination, name, fileIndices, totalSize, files, scheduledAt ->
                if (fileConflictBehavior == FileConflictBehavior.ASK) {
                    scope.launch {
                        val fullPathStr = onResolveDestinationPath(url, destination, name)
                        val file = File(fullPathStr)
                        val absolutePath = file.absolutePath

                        val conflictingDownload = state.downloads.find {
                            File(it.savePath).absolutePath == absolutePath
                        }

                        if (file.exists() || conflictingDownload != null) {
                            conflictData = ConflictData(
                                url = url,
                                savePath = destination,
                                name = name,
                                fileName = file.name,
                                fullPathStr = fullPathStr,
                                fileIndices = fileIndices,
                                conflictingDownloadId = conflictingDownload?.id
                            )
                        } else {
                            onEvent(DownloadsEvent.AddDownload(url, destination, name, fileIndices, totalSize, files, scheduledAt))
                        }
                    }
                } else {
                    // Without the conflict dialog in the way, a second copy of the same source into
                    // the same folder would otherwise be queued quietly.
                    state.downloads.findDuplicateOf(url, destination)?.let { existing ->
                        onNotify(
                            strings.downloads.advisories.duplicateSource(existing.name),
                            NotificationType.WARNING
                        )
                    }
                    onEvent(DownloadsEvent.AddDownload(url, destination, name, fileIndices, totalSize, files, scheduledAt))
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
                            data.conflictingDownloadId?.let { id ->
                                onEvent(DownloadsEvent.Remove(id, true))
                            }
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
            // An unfinished transfer has partial data on the line, and a source that cannot be
            // continued means that data is the only thing the user would have left.
            notice = if (download.state == DownloadState.DOWNLOADING || download.state == DownloadState.QUEUED) {
                strings.downloads.advisories.removeInProgress(download.percentDone())
            } else {
                null
            },
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

    downloadToPause?.let { download ->
        PauseRiskDialog(
            download = download,
            onDismiss = { downloadToPause = null },
            onConfirm = {
                onEvent(DownloadsEvent.Pause(download.id))
                downloadToPause = null
            }
        )
    }

    // Resolve by id so the dialog keeps showing live progress while the download runs; if the
    // download is removed/cleared meanwhile, nothing renders and the dialog effectively closes.
    detailsId?.let { id ->
        state.downloads.find { it.id == id }?.let { download ->
            DownloadDetailsDialog(
                download = download,
                onDismiss = { detailsId = null }
            )
        }
    }

    // ── Portable import: select a moved artifact, then detect it by content (never by name, §23). ──
    if (showArtifactPicker) {
        FilePickerDialog(
            onDismiss = { showArtifactPicker = false },
            onPick = { path ->
                showArtifactPicker = false
                val artifactPath = path.toString()
                scope.launch {
                    val info = onDetectPortable(artifactPath)
                    if (info == null) {
                        onNotify(strings.downloads.portable.notPortable(path.fileName.toString()), NotificationType.ERROR)
                    } else {
                        importDestination = path.toAbsolutePath().parent?.toString() ?: artifactPath
                        pendingImport = artifactPath to info
                    }
                }
            },
            mode = FilePickerMode.File,
            title = strings.downloads.portable.dialogTitle
        )
    }

    pendingImport?.let { (artifactPath, info) ->
        ImportPortableDialog(
            info = info,
            destinationDir = importDestination,
            busy = importBusy,
            onBrowseDestination = { showDestPicker = true },
            onImport = {
                if (!importBusy) {
                    importBusy = true
                    scope.launch {
                        val ok = onImportPortable(artifactPath, importDestination)
                        importBusy = false
                        pendingImport = null
                        if (ok) onNotify(strings.downloads.portable.imported, NotificationType.INFO)
                        else onNotify(
                            strings.downloads.portable.notPortable(Path.of(artifactPath).fileName.toString()),
                            NotificationType.ERROR
                        )
                    }
                }
            },
            onDismiss = { if (!importBusy) pendingImport = null }
        )
    }

    if (showDestPicker) {
        FilePickerDialog(
            onDismiss = { showDestPicker = false },
            onPick = { path ->
                importDestination = path.toString()
                showDestPicker = false
            },
            mode = FilePickerMode.Directory,
            initialPath = runCatching { Path.of(importDestination) }.getOrNull()
                ?.takeIf { java.nio.file.Files.isDirectory(it) }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        DownloadsToolbar(
            hasActiveDownloads = hasActive,
            hasPausedDownloads = hasPaused,
            hasCompletedDownloads = hasCompleted,
            searchQuery = state.searchQuery,
            onEvent = handleEvent,
            onAddDownload = { addDialogSource = null; showAddDialog = true },
            onImportPortable = { showArtifactPicker = true }
        )

        if (state.downloads.isEmpty()) {
            DownloadsEmptyState(
                onAddDownload = { addDialogSource = null; showAddDialog = true }
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
                onPauseRequested = handlePauseRequest,
                onResumeRequested = handleResumeRequest,
                onShowDetailsRequested = { id -> detailsId = id },
                onSelect = { id -> selectedId = id },
                hideResumeForQueued = hideResumeForQueued,
                capabilities = state.capabilities
            )
        }
    }
}
