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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.repository.PortableInfo
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.blazeStrings
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.osintegration.displaySource
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.presentation.components.notifications.NotificationType
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsState
import org.blaze.presentation.screens.downloads.findDuplicateOf
import org.blaze.presentation.screens.downloads.pauseLosesProgress
import org.blaze.presentation.screens.downloads.percentDone
import org.blaze.presentation.screens.downloads.resumeStartsOver
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.koin.compose.koinInject
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
    // A set (not a single id) so the leading checkboxes can build a multi-selection for bulk actions.
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()
    var defaultDeleteBehavior by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val searchFocusRequester = remember { FocusRequester() }
    val clipboard = koinInject<SystemClipboard>()

    // ── Portable-import flow: pick the moved artifact, preview it, choose a destination, resume. ──
    var showArtifactPicker by remember { mutableStateOf(false) }
    var showDestPicker by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Pair<String, PortableInfo>?>(null) }
    var importDestination by remember { mutableStateOf("") }

    // Surface queued external opens (torrent files / magnets / link lists / partial-download
    // candidates from the OS or a window drop) one at a time. The request is acked as it is shown:
    // it has then reached the user's workflow, and dismissing the dialog is the user's choice - it
    // must not resurrect the dialog. Acking shrinks the queue, which re-runs this effect for the
    // next one. Torrents, magnets, links and link lists go through the *normal* add-download
    // dialog (a link list lands in its batch review step); a dropped `.part` file or folder goes
    // through the portable-import preview after a content-based detection (never by name, §23),
    // so the transfer continues from the bytes already on disk.
    LaunchedEffect(externalRequests, showAddDialog, pendingImport) {
        if (!showAddDialog && pendingImport == null && externalRequests.isNotEmpty()) {
            val request = externalRequests.first()
            onExternalShown(request)
            if (request is ExternalOpenRequest.PartialDownload) {
                val artifactPath = request.displaySource
                val info = onDetectPortable(artifactPath)
                if (info == null) {
                    onNotify(
                        strings.downloads.portable.notPortable(request.path.fileName.toString()),
                        NotificationType.ERROR,
                    )
                } else {
                    // A folder resumes in place; a loose partial file resumes in its parent folder.
                    importDestination = if (java.nio.file.Files.isDirectory(request.path)) artifactPath
                    else request.path.parent?.toString() ?: artifactPath
                    pendingImport = artifactPath to info
                }
            } else {
                addDialogSource = request.displaySource
                showAddDialog = true
            }
        }
    }

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

    // ── Selection helpers ────────────────────────────────────────────────────
    // Drop ids whose download left the list (removed / cleared / filtered out by the engine) so the
    // selection can never point at a row that no longer exists.
    val visibleIds = state.filteredDownloads.map { it.id }.toSet()
    LaunchedEffect(state.filteredDownloads) {
        if (selectedIds.isNotEmpty()) {
            val pruned = selectedIds.filter { it in state.downloads.map { d -> d.id }.toSet() }.toSet()
            if (pruned != selectedIds) selectedIds = pruned
        }
    }

    val toggleSelect: (String, Boolean) -> Unit = { id, toggle ->
        selectedIds = when {
            toggle -> if (id in selectedIds) selectedIds - id else selectedIds + id
            selectedIds == setOf(id) -> emptySet()
            else -> setOf(id)
        }
    }
    val clearSelection: () -> Unit = { selectedIds = emptySet() }
    val selectAllVisible: () -> Unit = {
        selectedIds = if (visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)) emptySet() else visibleIds
    }
    // Bulk transport controls reuse the single-id commands the model already handles, applied to each
    // selected row. Bulk removal is list-only (never deletes files) so it stays reversible and needs no
    // per-row confirmation dialog.
    val bulkRemove: () -> Unit = {
        selectedIds.forEach { onEvent(DownloadsEvent.Remove(it, deleteFile = false)) }
        clearSelection()
    }
    val bulkPause: () -> Unit = {
        selectedIds.forEach { id -> onEvent(DownloadsEvent.Pause(id)) }
        clearSelection()
    }
    val bulkResume: () -> Unit = {
        selectedIds.forEach { id -> onEvent(DownloadsEvent.Resume(id)) }
        clearSelection()
    }
    val bulkCancel: () -> Unit = {
        selectedIds.forEach { id -> onEvent(DownloadsEvent.Cancel(id)) }
        clearSelection()
    }
    val toggleSelectedPauseResume: () -> Unit = {
        if (selectedIds.size == 1) {
            val id = selectedIds.first()
            state.downloads.find { it.id == id }?.let { download ->
                when (download.state) {
                    DownloadState.DOWNLOADING, DownloadState.SEEDING -> handlePauseRequest(download)
                    DownloadState.PAUSED, DownloadState.QUEUED -> handleResumeRequest(download)
                    else -> {}
                }
            }
        }
    }

    val openAddDialog: () -> Unit = { addDialogSource = null; showAddDialog = true }
    val pasteFromClipboard: () -> Unit = {
        addDialogSource = clipboard.paste()
        showAddDialog = true
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            initialSource = addDialogSource,
            onAdd = { url, destination, name, fileIndices, totalSize, files, scheduledAt, priority ->
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
                                conflictingDownloadId = conflictingDownload?.id,
                                priority = priority
                            )
                        } else {
                            onEvent(DownloadsEvent.AddDownload(url, destination, name, fileIndices, totalSize, files, scheduledAt, priority))
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
                    onEvent(DownloadsEvent.AddDownload(url, destination, name, fileIndices, totalSize, files, scheduledAt, priority))
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
                            onEvent(DownloadsEvent.AddDownload(data.url, data.savePath, data.name, data.fileIndices, priority = data.priority))
                        }
                    }
                    FileConflictBehavior.RENAME -> {
                        onEvent(DownloadsEvent.AddDownload(data.url, data.savePath, data.name, data.fileIndices, priority = data.priority))
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                when {
                    mod && event.key == Key.N -> { openAddDialog(); true }
                    mod && event.key == Key.F -> { searchFocusRequester.requestFocus(); true }
                    mod && event.key == Key.A -> { selectAllVisible(); true }
                    event.key == Key.Delete && selectedIds.isNotEmpty() -> { bulkRemove(); true }
                    event.key == Key.Escape && selectedIds.isNotEmpty() -> { clearSelection(); true }
                    event.key == Key.Spacebar && selectedIds.isNotEmpty() -> { toggleSelectedPauseResume(); true }
                    else -> false
                }
            }
    ) {
        DownloadsToolbar(
            hasActiveDownloads = hasActive,
            hasPausedDownloads = hasPaused,
            hasCompletedDownloads = hasCompleted,
            hasSelection = selectedIds.isNotEmpty(),
            selectedCount = selectedIds.size,
            searchQuery = state.searchQuery,
            snailModeEnabled = state.snailModeEnabled,
            searchFocusRequester = searchFocusRequester,
            onEvent = handleEvent,
            onAddDownload = openAddDialog,
            onImportPortable = { showArtifactPicker = true },
            onSelectAll = selectAllVisible,
            onClearSelection = clearSelection,
            onBulkPause = bulkPause,
            onBulkResume = bulkResume,
            onBulkCancel = bulkCancel,
            onBulkRemove = bulkRemove,
        )

        if (state.downloads.isEmpty()) {
            DownloadsEmptyState(
                onAddDownload = openAddDialog,
                onPaste = if (state.capabilities.canCopy) pasteFromClipboard else null,
            )
        } else {
            DownloadsFilterSortBar(
                filter = state.filter,
                counts = state.counts,
                sortField = state.sortField,
                sortAscending = state.sortAscending,
                onEvent = onEvent,
            )

            val activeCount = remember(state.downloads) {
                state.downloads.count { it.state == DownloadState.DOWNLOADING }
            }
            val hideResumeForQueued = activeCount >= state.maxConcurrentDownloads

            DownloadsList(
                downloads = state.filteredDownloads,
                selectedIds = selectedIds,
                listState = listState,
                onEvent = onEvent,
                onRemoveRequested = handleRemoveRequest,
                onPauseRequested = handlePauseRequest,
                onResumeRequested = handleResumeRequest,
                onShowDetailsRequested = { id -> detailsId = id },
                onSelect = toggleSelect,
                hideResumeForQueued = hideResumeForQueued,
                capabilities = state.capabilities
            )
        }
    }
}
