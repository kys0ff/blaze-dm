package org.blaze.presentation.screens.downloads

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.repository.PortableInfo
import org.blaze.domain.usecase.AddDownloadUseCase
import org.blaze.domain.usecase.CancelDownloadUseCase
import org.blaze.domain.usecase.ClearCompletedDownloadsUseCase
import org.blaze.domain.usecase.DetectPortableDownloadUseCase
import org.blaze.domain.usecase.FetchMetadataUseCase
import org.blaze.domain.usecase.GetDestinationPathUseCase
import org.blaze.domain.usecase.GetDownloadsUseCase
import org.blaze.domain.usecase.ImportPortableDownloadUseCase
import org.blaze.domain.usecase.PauseAllDownloadsUseCase
import org.blaze.domain.usecase.PauseDownloadUseCase
import org.blaze.domain.usecase.RemoveDownloadUseCase
import org.blaze.domain.usecase.ResumeAllDownloadsUseCase
import org.blaze.domain.usecase.ResumeDownloadUseCase
import org.blaze.domain.usecase.RetryDownloadUseCase
import org.blaze.domain.usecase.SetPriorityDownloadUseCase
import org.blaze.data.AppSettingsRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.files.SystemFileService
import org.blaze.presentation.components.DownloadsColumn
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class DownloadsScreenModel(
    getDownloads: GetDownloadsUseCase,
    private val fetchMetadataUseCase: FetchMetadataUseCase,
    private val addDownload: AddDownloadUseCase,
    private val getDestinationPath: GetDestinationPathUseCase,
    val settingsRepository: EngineSettingsRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val pauseDownload: PauseDownloadUseCase,
    private val resumeDownload: ResumeDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val removeDownload: RemoveDownloadUseCase,
    private val retryDownload: RetryDownloadUseCase,
    private val setPriority: SetPriorityDownloadUseCase,
    private val pauseAllDownloads: PauseAllDownloadsUseCase,
    private val resumeAllDownloads: ResumeAllDownloadsUseCase,
    private val clearCompletedDownloads: ClearCompletedDownloadsUseCase,
    private val detectPortableDownload: DetectPortableDownloadUseCase,
    private val importPortableDownload: ImportPortableDownloadUseCase,
    private val systemFileService: SystemFileService,
    private val clipboard: SystemClipboard,
    private val externalOpenInbox: ExternalOpenInbox,
    private val commandBus: org.blaze.presentation.application.AppCommandBus,
) : ScreenModel {

    /**
     * Torrent/magnet requests delivered by the operating system (argv at cold start, macOS open
     * events, IPC forwards from a second launcher). They are queued in the inbox before this screen
     * exists, so nothing is lost; the UI shows each one through the normal add-download dialog and
     * [externalHandled] acks it out of the queue.
     */
    val externalRequests: StateFlow<List<ExternalOpenRequest>> = externalOpenInbox.pending

    fun externalHandled(request: ExternalOpenRequest) {
        externalOpenInbox.ack(request)
    }

    private val _searchQuery = MutableStateFlow("")

    /**
     * Persisted tool-window layout (column widths, Details visibility, split fraction, pause
     * confirmation), seeded from disk on construction and written back on change. Kept in one flow
     * so the frequent layout edits debounce to a single `app.json` write.
     */
    private data class LayoutPrefs(
        val columnWidths: Map<String, Float>,
        val hiddenColumns: Set<String>,
        val detailsVisible: Boolean,
        val splitFraction: Float,
        val warnBeforeDiscarding: Boolean,
        val viewMode: DetailsViewMode,
        val moveTo: DetailsMoveTo,
        val groupTabs: Boolean,
    )

    /** Column order + narrowing chosen through the table header / filter bar. */
    private val _sort = MutableStateFlow(
        downloadSortFieldFromName(appSettingsRepository.settings.value.downloadsSortField) to
            appSettingsRepository.settings.value.downloadsSortAscending
    )
    private val _filter = MutableStateFlow(DownloadFilter.ALL)
    /**
     * The row selection, held as pure [Selection] data and reduced through [reduceSelection] so the
     * click / Ctrl / Shift / arrow rules live in one testable place. Its ids are pruned against the
     * live list when folded into [state], so a removed row can never stay selected.
     */
    private val _selection = MutableStateFlow(Selection())
    private val _layout = MutableStateFlow(
        appSettingsRepository.settings.value.let { saved ->
            LayoutPrefs(
                columnWidths = saved.downloadsColumnWidths,
                hiddenColumns = saved.downloadsHiddenColumns.toSet(),
                detailsVisible = saved.detailsPaneVisible,
                splitFraction = saved.detailsSplitFraction,
                warnBeforeDiscarding = saved.warnBeforeDiscardingProgress,
                viewMode = detailsViewModeFromName(saved.detailsViewMode),
                moveTo = detailsMoveToFromName(saved.detailsMoveTo),
                groupTabs = saved.groupDetailsTabs,
            )
        }
    )

    private var layoutPersistJob: Job? = null

    /** Coalesces rapid layout edits (divider / column drags) into one debounced disk write. */
    private fun persistLayout() {
        layoutPersistJob?.cancel()
        layoutPersistJob = screenModelScope.launch {
            delay(LAYOUT_PERSIST_DEBOUNCE_MS.milliseconds)
            val layout = _layout.value
            val (field, ascending) = _sort.value
            appSettingsRepository.updateSettings {
                it.copy(
                    downloadsColumnWidths = layout.columnWidths,
                    downloadsHiddenColumns = layout.hiddenColumns.toList(),
                    downloadsSortField = field.name,
                    downloadsSortAscending = ascending,
                    detailsPaneVisible = layout.detailsVisible,
                    detailsSplitFraction = layout.splitFraction,
                    warnBeforeDiscardingProgress = layout.warnBeforeDiscarding,
                    detailsViewMode = layout.viewMode.name,
                    detailsMoveTo = layout.moveTo.name,
                    groupDetailsTabs = layout.groupTabs,
                )
            }
        }
    }

    /** The same shared list the state renders, kept so the transition watcher can re-check a fact. */
    private val downloadsFlow = getDownloads()

    /** Latest snapshot seen by [watchTransitions], used to confirm a failure was not a retry hop. */
    private val latestDownloads = MutableStateFlow<List<Download>>(emptyList())

    /** Capabilities are fixed for the host; resolved once so the row menu can hide unsupported actions. */
    private val capabilities = DownloadCapabilities(
        canOpenFiles = systemFileService.canOpenFiles,
        canRevealInFolder = systemFileService.canRevealInFolder,
        canBrowseLinks = systemFileService.canBrowseLinks,
        canCopy = clipboard.isSupported
    )

    private val baseState = combine(
        downloadsFlow,
        _searchQuery,
        settingsRepository.settings,
        _sort,
        _filter,
    ) { downloads, query, settings, (field, ascending), filter ->
        val searched = if (query.isBlank()) {
            downloads
        } else {
            downloads.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.url.contains(query, ignoreCase = true)
            }
        }
        DownloadsState(
            downloads = downloads,
            searchQuery = query,
            sortField = field,
            sortAscending = ascending,
            filter = filter,
            counts = downloads.toCounts(),
            maxConcurrentDownloads = settings.maxConcurrentDownloads,
            snailModeEnabled = settings.snailModeEnabled,
            snailSpeedKbps = settings.snailSpeedKbps,
            capabilities = capabilities,
            filteredDownloads = searched.filterAndSort(filter, field, ascending),
        )
    }

    val state: StateFlow<DownloadsState> = combine(
        baseState,
        _layout,
        _selection,
    ) { base, layout, selection ->
        val liveIds = base.downloads.mapTo(HashSet()) { it.id }
        base.copy(
            selectedIds = selection.ids.filterTo(LinkedHashSet()) { it in liveIds },
            columnWidths = layout.columnWidths,
            hiddenColumns = layout.hiddenColumns,
            detailsVisible = layout.detailsVisible,
            detailsSplitFraction = layout.splitFraction,
            warnBeforeDiscardingProgress = layout.warnBeforeDiscarding,
            viewMode = layout.viewMode,
            moveTo = layout.moveTo,
            groupTabs = layout.groupTabs,
        )
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), DownloadsState(capabilities = capabilities))

    private val _effects = MutableSharedFlow<DownloadsEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        screenModelScope.launch { watchTransitions() }
        // The title-bar menu raises app-wide commands; the ones that are inherently about *this view*
        // (its details pane and its row selection) are folded back into the model's own events here. The
        // global queue commands (pause/resume all) and exit are handled up in the application shell, and
        // navigation / new-download / focus-search are handled by the UI layers, so they are ignored here.
        screenModelScope.launch {
            commandBus.commands.collect { command ->
                when (command) {
                    org.blaze.presentation.application.AppCommand.ToggleDetails ->
                        onEvent(DownloadsEvent.ToggleDetails(!state.value.detailsVisible))
                    org.blaze.presentation.application.AppCommand.SelectAll ->
                        onEvent(DownloadsEvent.SelectAllRows)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Announces the changes a user did not cause by pressing anything: a file that finished, a transfer
     * that gave up, progress a forced restart threw away. They are read off the list's own stream, so
     * the notices arrive even while the window shows a different screen.
     *
     * The first snapshot is only remembered, never announced - the downloads restored at startup are
     * history the user already knows about.
     */
    private suspend fun watchTransitions() {
        var previous: List<Download>? = null
        downloadsFlow.collect { current ->
            val before = previous
            previous = current
            latestDownloads.value = current
            if (before == null) return@collect

            diffDownloadTransitions(before, current).forEach { advisory ->
                // Each notice gets its own job: waiting out a retry must not stall the list stream.
                screenModelScope.launch { deliver(advisory) }
            }
        }
    }

    private suspend fun deliver(advisory: DownloadAdvisory) {
        when (advisory) {
            is DownloadAdvisory.Completed ->
                _effects.emit(DownloadsEffect.DownloadCompleted(advisory.download.name))

            is DownloadAdvisory.Restarted ->
                _effects.emit(DownloadsEffect.DownloadRestarted(advisory.download.name, advisory.lostPercent))

            is DownloadAdvisory.Failed -> {
                // Automatic retries hop through Failed on the way back to Queued, and telling the
                // user about an attempt that is already being retried is noise, not a warning.
                delay(RETRY_GRACE_MILLIS.milliseconds)
                val live = latestDownloads.value.find { it.id == advisory.download.id } ?: return
                if (live.state != DownloadState.FAILED) return
                _effects.emit(DownloadsEffect.DownloadFailed(live.name, live.error))
            }
        }
    }

    fun onEvent(event: DownloadsEvent) {
        when (event) {
            is DownloadsEvent.SearchChanged -> _searchQuery.value = event.query

            is DownloadsEvent.SortBy -> {
                _sort.update { (field, ascending) ->
                    if (field == event.field) field to !ascending
                    // A freshly chosen column starts in its natural direction: biggest/most-advanced
                    // first for date, speed, ETA and progress, A-z / smallest first for the rest.
                    else event.field to (event.field != DownloadSortField.DATE_ADDED &&
                        event.field != DownloadSortField.SPEED &&
                        event.field != DownloadSortField.ETA &&
                        event.field != DownloadSortField.PROGRESS)
                }
                persistLayout()
            }

            is DownloadsEvent.FilterBy -> _filter.value = event.filter

            is DownloadsEvent.SelectRow -> reduceSelectionNow(
                SelectionAction.Click(event.id, event.additive, event.range)
            )
            DownloadsEvent.SelectAllRows -> reduceSelectionNow(SelectionAction.SelectAll)
            DownloadsEvent.ClearRowSelection -> reduceSelectionNow(SelectionAction.Clear)
            is DownloadsEvent.MoveSelection -> reduceSelectionNow(
                SelectionAction.Move(event.delta, event.extend)
            )

            is DownloadsEvent.ResizeColumn -> {
                _layout.update { it.copy(columnWidths = it.columnWidths + (event.columnId to event.widthDp)) }
                persistLayout()
            }

            // Name is the row's identity - a table without it cannot be read or clicked by name, so the
            // request to hide it is dropped rather than trusted to the menu disabling the item.
            is DownloadsEvent.ToggleColumn -> {
                if (event.columnId != DownloadsColumn.Name.id) {
                    _layout.update { layout ->
                        val hidden =
                            if (event.visible) layout.hiddenColumns - event.columnId
                            else layout.hiddenColumns + event.columnId
                        layout.copy(hiddenColumns = hidden)
                    }
                    persistLayout()
                }
            }

            DownloadsEvent.ResetColumnWidths -> {
                _layout.update { it.copy(columnWidths = emptyMap()) }
                persistLayout()
            }

            is DownloadsEvent.SetSplitFraction -> {
                _layout.update { it.copy(splitFraction = event.fraction.coerceIn(0.15f, 0.85f)) }
                persistLayout()
            }

            is DownloadsEvent.ToggleDetails -> {
                _layout.update { it.copy(detailsVisible = event.visible) }
                persistLayout()
            }

            is DownloadsEvent.SetWarnBeforeDiscardingProgress -> {
                _layout.update { it.copy(warnBeforeDiscarding = event.enabled) }
                persistLayout()
            }

            is DownloadsEvent.SetDetailsViewMode -> {
                _layout.update { it.copy(viewMode = event.mode) }
                persistLayout()
            }

            is DownloadsEvent.SetDetailsMoveTo -> {
                _layout.update { it.copy(moveTo = event.position) }
                persistLayout()
            }

            is DownloadsEvent.ToggleGroupDetailsTabs -> {
                _layout.update { it.copy(groupTabs = event.enabled) }
                persistLayout()
            }

            is DownloadsEvent.ResizeDetails -> {
                // A resize names the edge the pane stretches toward, so it also docks the pane there
                // where that edge is a supported side; the size is kept as the list's share.
                _layout.update { l ->
                    val (fraction, moveTo) = when (event.preset) {
                        DetailsResize.STRETCH_LEFT -> 0.30f to DetailsMoveTo.LEFT_TOP
                        DetailsResize.STRETCH_RIGHT -> 0.30f to DetailsMoveTo.RIGHT_TOP
                        DetailsResize.STRETCH_BOTTOM -> 0.30f to DetailsMoveTo.BOTTOM_LEFT
                        DetailsResize.STRETCH_TOP -> 0.30f to l.moveTo
                        DetailsResize.MAXIMIZE -> 0.15f to l.moveTo
                    }
                    l.copy(splitFraction = fraction.coerceIn(0.15f, 0.85f), moveTo = moveTo)
                }
                persistLayout()
            }

            is DownloadsEvent.ToggleSnailMode -> screenModelScope.launch {
                // Operational switch: persist immediately so the engine's limiter flow reacts at once.
                settingsRepository.updateSettings { it.copy(snailModeEnabled = event.enabled) }
            }

            is DownloadsEvent.AddDownload -> screenModelScope.launch {
                try {
                    addDownload(
                        url = event.url,
                        savePath = event.savePath,
                        name = event.name,
                        fileIndices = event.fileIndices,
                        totalSize = event.totalSize,
                        files = event.files,
                        scheduledAt = event.scheduledAt,
                        priority = event.priority,
                    )
                } catch (e: Exception) {
                    fail("Failed to add download", e)
                }
            }

            is DownloadsEvent.SetPriority -> op("Failed to change priority") { setPriority(event.id, event.priority) }
            is DownloadsEvent.Pause -> op("Failed to pause download") { pauseDownload(event.id) }
            // A resume only makes sense for a row parked mid-flight. Handing a finished or failed
            // task to the engine would silently restart it and re-fetch bytes the user already has,
            // so the command is dropped when the row cannot obey it; fetching a finished file again
            // is the separate, explicitly offered Retry.
            is DownloadsEvent.Resume -> {
                val target = state.value.downloads.find { it.id == event.id }
                if (target != null && target.canResume()) {
                    op("Failed to resume download") { resumeDownload(event.id) }
                }
            }
            is DownloadsEvent.Remove -> op("Failed to remove download") { removeDownload(id = event.id, deleteFile = event.deleteFile) }
            is DownloadsEvent.Retry -> op("Failed to retry download") { retryDownload(event.id) }

            is DownloadsEvent.Cancel -> screenModelScope.launch {
                try {
                    cancelDownload(event.id)
                    _effects.emit(DownloadsEffect.ShowMessage("Download canceled"))
                } catch (e: Exception) {
                    fail("Failed to cancel download", e)
                }
            }

            DownloadsEvent.PauseAll -> op("Failed to pause all downloads") { pauseAllDownloads() }
            DownloadsEvent.ResumeAll -> op("Failed to resume all downloads") { resumeAllDownloads() }
            DownloadsEvent.ClearCompleted -> op("Failed to clear completed downloads") { clearCompletedDownloads() }

            is DownloadsEvent.OpenFile -> runDesktop(event.id) { download ->
                systemFileService.openPath(download.destinationPath())
            }

            is DownloadsEvent.ShowInFolder -> runDesktop(event.id) { download ->
                systemFileService.revealInFolder(download.destinationPath())
            }

            is DownloadsEvent.OpenSourceLink -> runDesktop(event.id) { download ->
                if (download.url.startsWith("http://", ignoreCase = true) ||
                    download.url.startsWith("https://", ignoreCase = true)
                ) {
                    systemFileService.openInBrowser(download.url)
                } else {
                    Result.failure(IllegalStateException("No web link to open for this download"))
                }
            }

            is DownloadsEvent.CopyDownloadLink -> runDesktop(event.id) { download ->
                clipboard.copy(download.url)
            }

            is DownloadsEvent.CopyFileLocation -> runDesktop(event.id) { download ->
                clipboard.copy(download.destinationPath().toAbsolutePath().normalize().toString())
            }
        }
    }

    /**
     * Folds a selection [action] into [_selection], reducing against the current visible row order.
     * Reading the visible list off [state] (rather than the raw downloads) keeps range/arrow maths
     * aligned with what the user actually sees after search, filter and sort.
     */
    private fun reduceSelectionNow(action: SelectionAction) {
        val visible = state.value.filteredDownloads.map { it.id }
        val before = _selection.value.ids
        _selection.update { reduceSelection(it, visible, action) }

        // Details is a collapsed tool window: selecting a row is what earns it a place on the frame,
        // so the first selection re-opens the pane (and the reopen is persisted like any other layout
        // change). Clearing the selection deliberately does NOT close it again - closing is the user's
        // call, made with the toolbar or title-bar toggle.
        if (_selection.value.ids.isNotEmpty() && before.isEmpty() && !_layout.value.detailsVisible) {
            _layout.update { it.copy(detailsVisible = true) }
            persistLayout()
        }
    }

    /** Runs a single command, reporting any failure as an error effect with the given [context] prefix. */
    private fun op(context: String, block: suspend () -> Unit) = screenModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            fail(context, e)
        }
    }

    private suspend fun fail(context: String, error: Throwable) {
        _effects.emit(DownloadsEffect.ShowError("$context: ${error.message}"))
    }

    /**
     * Shared plumbing for the desktop-integration actions: resolve the download by id, run the
     * [action] and surface any failure as an error notification. Keeps each event branch focused
     * on choosing the target rather than repeating lookup and error handling.
     */
    private fun runDesktop(id: String, action: suspend (Download) -> Result<Unit>) {
        val download = state.value.downloads.find { it.id == id } ?: return
        screenModelScope.launch {
            action(download).onFailure { error ->
                _effects.emit(DownloadsEffect.ShowError(error.message ?: "The action could not be completed"))
            }
        }
    }

    /**
     * The on-disk target for Open / Show-in-folder / Copy-location. HTTP downloads resolve to the
     * file itself, while a torrent's destination is the folder its selected files live under.
     */
    private fun Download.destinationPath(): Path = Path.of(savePath)

    suspend fun fetchMetadata(url: String): DownloadMetadata? = fetchMetadataUseCase(url)

    suspend fun resolveDestinationPath(url: String, savePath: String, name: String?): String =
        getDestinationPath(url, savePath, name)

    /**
     * Reads a moved file/folder as a candidate portable download and returns a display-only summary,
     * or null when it is not a Blaze portable artifact. The UI previews this before importing.
     */
    suspend fun detectPortable(path: String): PortableInfo? = detectPortableDownload(path)

    /**
     * Imports the detected portable [artifactPath] into [destinationDir], reconstructing local resume
     * state so the transfer continues. True when a resumable download was queued.
     */
    suspend fun importPortable(artifactPath: String, destinationDir: String): Boolean =
        importPortableDownload(artifactPath, destinationDir)

    private companion object {
        /** How long a download must stay failed before it is worth interrupting the user for. */
        const val RETRY_GRACE_MILLIS = 5_000L

        /** How long to wait after the last layout tweak before writing `app.json`. */
        const val LAYOUT_PERSIST_DEBOUNCE_MS = 350L
    }
}
