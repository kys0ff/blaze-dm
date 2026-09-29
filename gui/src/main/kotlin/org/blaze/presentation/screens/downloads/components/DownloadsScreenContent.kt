package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
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
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.Island
import org.blaze.presentation.components.notifications.NotificationType
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsState
import org.blaze.presentation.screens.downloads.SelectionActions
import org.blaze.presentation.screens.downloads.canCancel
import org.blaze.presentation.screens.downloads.canPause
import org.blaze.presentation.screens.downloads.canRemove
import org.blaze.presentation.screens.downloads.canResume
import org.blaze.presentation.screens.downloads.findDuplicateOf
import org.blaze.presentation.screens.downloads.pauseLosesProgress
import org.blaze.presentation.screens.downloads.percentDone
import org.blaze.presentation.screens.downloads.resumeStartsOver
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Files
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
    // Non-null while the bulk "clear completed" confirmation is up; the value is the scope captured
    // when the button was pressed, so the dialog can name exactly what it is about to drop.
    var clearCompletedPrompt by remember { mutableStateOf<ClearCompletedScope?>(null) }
    // The Details pane is driven by the single-row selection, so there is no separate "which row's
    // details" state to track — selecting exactly one download is what shows its details.
    var conflictData by remember { mutableStateOf<ConflictData?>(null) }
    // Bulk "remove" is confirmed with the count it covers and a "do not ask again" opt-out (in-memory).
    var bulkRemovePrompt by remember { mutableStateOf<Int?>(null) }
    var bulkRemoveAskAgain by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    var defaultDeleteBehavior by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val searchFocusRequester = remember { FocusRequester() }
    val clipboard = koinInject<SystemClipboard>()

    // Commands the title-bar menu raises that this screen owns directly: open the add-download dialog,
    // or move focus into the in-list search field. (Transport / selection / layout ones are folded into
    // the model's own events, and navigation / exit live one level up.)
    val commandBus = koinInject<org.blaze.presentation.application.AppCommandBus>()
    LaunchedEffect(Unit) {
        commandBus.commands.collect { command ->
            when (command) {
                org.blaze.presentation.application.AppCommand.NewDownload -> {
                    addDialogSource = null
                    showAddDialog = true
                }
                org.blaze.presentation.application.AppCommand.FocusSearch ->
                    if (state.downloads.isNotEmpty()) runCatching { searchFocusRequester.requestFocus() }
                else -> Unit
            }
        }
    }

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
                    importDestination = if (Files.isDirectory(request.path)) artifactPath
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

    // Every concurrency slot is busy, so resuming a queued row would not actually start it; the
    // resume affordance is withheld for queued rows (single row, selection and bulk alike).
    val activeCount = remember(state.downloads) { state.downloads.count { it.state == DownloadState.DOWNLOADING } }
    val hideResumeForQueued = activeCount >= state.maxConcurrentDownloads

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
        if (download.pauseLosesProgress() && state.warnBeforeDiscardingProgress) downloadToPause = download
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

    // ── Selection (owned by the screen model) ──────────────────────────────────
    // The selected ids, the anchor and the multi-select rules live in DownloadsScreenModel (folded
    // into [DownloadsState.selectedIds]) so the status bar can read the count and every entry point —
    // row pointer, keyboard, header/context menus — reduces through the same tested rules.
    val selectedIds = state.selectedIds
    val selectedDownloads = state.downloads.filter { it.id in selectedIds }
    // Exactly one selected row feeds the Details pane; none or multi shows the empty hint.
    val detailsDownload: Download? =
        if (selectedIds.size == 1) state.downloads.find { it.id == selectedIds.first() } else null
    val selectionActions = SelectionActions.of(selectedDownloads, queuedResumeBlocked = hideResumeForQueued)

    val selectRow: (String, Boolean, Boolean) -> Unit = { id, additive, range ->
        onEvent(DownloadsEvent.SelectRow(id, additive, range))
    }
    val clearSelection: () -> Unit = { onEvent(DownloadsEvent.ClearRowSelection) }
    val selectAllVisible: () -> Unit = { onEvent(DownloadsEvent.SelectAllRows) }

    // Bulk transport controls reuse the single-id commands the model already handles, applied to each
    // selected row *that can obey them*: a button is only offered when some selected row would act on
    // it, and pressing it leaves the rows that cannot (a finished download, say) alone.
    val bulkPause: () -> Unit = {
        selectedDownloads.filter { it.canPause() }.forEach { onEvent(DownloadsEvent.Pause(it.id)) }
        clearSelection()
    }
    val bulkResume: () -> Unit = {
        selectedDownloads.filter { it.canResume(hideResumeForQueued) }.forEach { onEvent(DownloadsEvent.Resume(it.id)) }
        clearSelection()
    }
    val bulkCancel: () -> Unit = {
        selectedDownloads.filter { it.canCancel() }.forEach { onEvent(DownloadsEvent.Cancel(it.id)) }
        clearSelection()
    }
    // Bulk removal only leaves the list (never touches files), but it is still confirmed with the count
    // it covers — unless the user opted out of the prompt.
    val performBulkRemove: () -> Unit = {
        selectedDownloads.filter { it.canRemove() }.forEach { onEvent(DownloadsEvent.Remove(it.id, deleteFile = false)) }
        clearSelection()
    }
    val bulkRemove: () -> Unit = {
        val count = selectedDownloads.count { it.canRemove() }
        if (count > 0) {
            if (bulkRemoveAskAgain) bulkRemovePrompt = count else performBulkRemove()
        }
    }
    val toggleSelectedPauseResume: () -> Unit = {
        detailsDownload?.let { download ->
            when {
                download.canPause() -> handlePauseRequest(download)
                download.canResume(hideResumeForQueued) -> handleResumeRequest(download)
                else -> {}
            }
        }
    }

    // Enter opens the selected row when it has a finished artifact on disk, and otherwise reveals its
    // details by making sure the Details pane is shown (the pane already follows the selection).
    val handleEnterOpen: () -> Unit = {
        detailsDownload?.let { download ->
            val finished = download.state == DownloadState.COMPLETED || download.state == DownloadState.SEEDING
            if (finished && runCatching { File(download.savePath).exists() }.getOrDefault(false)) {
                onEvent(DownloadsEvent.OpenFile(download.id))
            } else {
                onEvent(DownloadsEvent.ToggleDetails(true))
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
                        // Adding the same source again is exactly the request: the download is created
                        // under a name that is free, a numbered copy of the one that collided - by the
                        // service for a torrent's folder, and by the engine for an HTTP file. Choosing
                        // rename here must not invent a name a layer that can see the whole folder has
                        // better rules for.
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
            onConfirm = { doNotAskAgain ->
                if (doNotAskAgain) onEvent(DownloadsEvent.SetWarnBeforeDiscardingProgress(false))
                onEvent(DownloadsEvent.Pause(download.id))
                downloadToPause = null
            }
        )
    }

    // Clearing is the only list edit that acts on rows the user never selected, so it is confirmed
    // with the exact scope it covers. Files on disk are untouched by design; seeds are stopped.
    clearCompletedPrompt?.let { scope ->
        ClearCompletedDialog(
            scope = scope,
            onDismiss = { clearCompletedPrompt = null },
            onConfirm = {
                onEvent(DownloadsEvent.ClearCompleted)
                clearCompletedPrompt = null
            }
        )
    }

    // Details moved from a modal dialog to the collapsible tool-window pane, driven by the selection.

    // Bulk removal is list-only but still touches many rows at once, so it is confirmed with the count
    // it covers and offers a "do not ask again" opt-out (mirrors the single-row remove dialog).
    bulkRemovePrompt?.let { count ->
        BulkRemoveDialog(
            count = count,
            onDismiss = { bulkRemovePrompt = null },
            onConfirm = { doNotAskAgain ->
                if (doNotAskAgain) bulkRemoveAskAgain = false
                performBulkRemove()
                bulkRemovePrompt = null
            },
        )
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
                ?.takeIf { Files.isDirectory(it) }
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
                    event.key == Key.Enter && selectedIds.size == 1 -> { handleEnterOpen(); true }
                    // Arrow keys drive the selection (the list holds focus); Shift extends the span.
                    event.key == Key.DirectionDown -> { onEvent(DownloadsEvent.MoveSelection(1, extend = event.isShiftPressed)); true }
                    event.key == Key.DirectionUp -> { onEvent(DownloadsEvent.MoveSelection(-1, extend = event.isShiftPressed)); true }
                    else -> false
                }
            }
    ) {
        // The downloads tool window: its toolbar/header, then the filter bar over the table (or the
        // empty state). In Islands this whole content floats as one rounded island; in classic mode
        // it fills its box edge to edge.
        val listContent: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                DownloadsToolbar(
                    hasActiveDownloads = hasActive,
                    hasPausedDownloads = hasPaused,
                    hasCompletedDownloads = hasCompleted,
                    hasSelection = selectedIds.isNotEmpty(),
                    selectedCount = selectedIds.size,
                    selectionActions = selectionActions,
                    searchQuery = state.searchQuery,
                    snailModeEnabled = state.snailModeEnabled,
                    searchFocusRequester = searchFocusRequester,
                    onEvent = handleEvent,
                    onAddDownload = openAddDialog,
                    onImportPortable = { showArtifactPicker = true },
                    onClearCompleted = { clearCompletedPrompt = ClearCompletedScope(state.counts.completed, state.counts.seeding) },
                    onSelectAll = selectAllVisible,
                    onClearSelection = clearSelection,
                    onBulkPause = bulkPause,
                    onBulkResume = bulkResume,
                    onBulkCancel = bulkCancel,
                    onBulkRemove = bulkRemove,
                    detailsVisible = state.detailsVisible,
                    onToggleDetails = { visible -> onEvent(DownloadsEvent.ToggleDetails(visible)) },
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
                        onEvent = onEvent,
                    )
                    DownloadsList(
                        downloads = state.filteredDownloads,
                        selectedIds = selectedIds,
                        listState = listState,
                        columnWidths = state.columnWidths,
                        sortField = state.sortField,
                        sortAscending = state.sortAscending,
                        onEvent = onEvent,
                        onRemoveRequested = handleRemoveRequest,
                        onPauseRequested = handlePauseRequest,
                        onResumeRequested = handleResumeRequest,
                        // Selecting the row is what surfaces its details now - no modal dialog.
                        onShowDetailsRequested = { id -> onEvent(DownloadsEvent.SelectRow(id)) },
                        onSelect = selectRow,
                        onSelectAll = selectAllVisible,
                        hideResumeForQueued = hideResumeForQueued,
                        capabilities = state.capabilities,
                    )
                }
            }
        }

        val listPane: @Composable () -> Unit = {
            if (BlazeLayout.useIslands) {
                Island(modifier = Modifier.fillMaxSize()) { listContent() }
            } else {
                Box(modifier = Modifier.fillMaxSize()) { listContent() }
            }
        }

        if (state.downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) { listPane() }
        } else {
            // The Details tool window: docked beside the list (with the split's orientation, order
            // and size driven by the pane's View Mode / Move To / Resize), or floated over the list.
            // An unpinned pane folds away on its own when nothing is selected to show.
            val viewMode = state.viewMode
            val moveTo = state.moveTo
            val detailsShown = state.detailsVisible && (!viewMode.autoHidesWhenEmpty || detailsDownload != null)
            val detailsContent: @Composable () -> Unit = {
                DownloadDetailsPanel(
                    download = detailsDownload,
                    onHide = { onEvent(DownloadsEvent.ToggleDetails(false)) },
                    modifier = Modifier.fillMaxSize(),
                    viewMode = viewMode,
                    moveTo = moveTo,
                    groupTabs = state.groupTabs,
                    onEvent = onEvent,
                )
            }
            val detailsPane: @Composable () -> Unit = {
                if (BlazeLayout.useIslands) {
                    Island(modifier = Modifier.fillMaxSize()) { detailsContent() }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) { detailsContent() }
                }
            }

            // Islands: the split's divider is a transparent gap (the frame shows through) with the
            // drag handle riding in it; classic keeps the visible hairline divider.
            val islandGap = BlazeLayout.IslandGap

            when {
                !detailsShown -> Box(modifier = Modifier.fillMaxWidth().weight(1f)) { listPane() }

                viewMode.isDocked -> {
                    // The persisted fraction is always the list's share; a left-docked pane is first,
                    // so the split's first-pane fraction is the list's complement.
                    val firstFraction = if (moveTo.detailsFirst) 1f - state.detailsSplitFraction else state.detailsSplitFraction
                    val splitState = remember(moveTo) { SplitLayoutState(firstFraction) }
                    // Push an external Move To / Resize change into the divider.
                    LaunchedEffect(firstFraction, splitState) { splitState.dividerPosition = firstFraction }
                    // Persist a divider drag, converted back to the list's share; drop(1) skips the seed.
                    LaunchedEffect(splitState, moveTo) {
                        snapshotFlow { splitState.dividerPosition }
                            .drop(1)
                            .collect { d ->
                                onEvent(DownloadsEvent.SetSplitFraction(if (moveTo.detailsFirst) 1f - d else d))
                            }
                    }
                    val first: @Composable () -> Unit = if (moveTo.detailsFirst) detailsPane else listPane
                    val second: @Composable () -> Unit = if (moveTo.detailsFirst) listPane else detailsPane
                    val gapDivider = DividerStyle(
                        color = if (BlazeLayout.useIslands) Color.Transparent else JewelTheme.globalColors.borders.normal,
                        metrics = DividerMetrics(thickness = islandGap, startIndent = 0.dp),
                    )
                    if (moveTo.isHorizontal) {
                        HorizontalSplitLayout(
                            first = first,
                            second = second,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            dividerStyle = gapDivider,
                            firstPaneMinWidth = if (moveTo.detailsFirst) BlazeLayout.ListPaneMinWidth else BlazeLayout.DetailsPaneMinWidth,
                            secondPaneMinWidth = if (moveTo.detailsFirst) BlazeLayout.DetailsPaneMinWidth else BlazeLayout.ListPaneMinWidth,
                            draggableWidth = BlazeLayout.SplitDragWidth,
                            state = splitState,
                        )
                    } else {
                        VerticalSplitLayout(
                            first = first,
                            second = second,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            dividerStyle = gapDivider,
                            firstPaneMinWidth = 260.dp,
                            secondPaneMinWidth = 160.dp,
                            draggableWidth = BlazeLayout.SplitDragWidth,
                            state = splitState,
                        )
                    }
                }

                else -> {
                    // Floating: the list fills the area and the pane hovers over its chosen edge.
                    val overlayAlignment = when (moveTo) {
                        DetailsMoveTo.LEFT_TOP -> Alignment.TopStart
                        DetailsMoveTo.LEFT_BOTTOM -> Alignment.BottomStart
                        DetailsMoveTo.RIGHT_TOP -> Alignment.TopEnd
                        DetailsMoveTo.RIGHT_BOTTOM -> Alignment.BottomEnd
                        DetailsMoveTo.BOTTOM_LEFT -> Alignment.BottomStart
                        DetailsMoveTo.BOTTOM_RIGHT -> Alignment.BottomEnd
                    }
                    // A floating pane is an overlay, so it stays narrower than the docked split's third
                    // (BlazeLayout.DetailsDefaultListFraction) rather than covering half the list.
                    val widthFraction = if (moveTo.isBottom) 0.5f else 0.34f
                    val heightFraction = if (moveTo.isBottom) 0.42f else 0.62f
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        listPane()
                        Box(
                            modifier = Modifier
                                .align(overlayAlignment)
                                .padding(12.dp)
                                .fillMaxWidth(widthFraction)
                                .fillMaxHeight(heightFraction),
                        ) { detailsPane() }
                    }
                }
            }
        }
    }
}
