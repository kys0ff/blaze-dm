package org.blaze.presentation.screens.downloads

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.files.SystemFileService
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class DownloadsScreenModel(
    getDownloads: GetDownloadsUseCase,
    private val fetchMetadataUseCase: FetchMetadataUseCase,
    private val addDownload: AddDownloadUseCase,
    private val getDestinationPath: GetDestinationPathUseCase,
    val settingsRepository: EngineSettingsRepository,
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
    private val externalOpenInbox: ExternalOpenInbox
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

    /** Column order + narrowing chosen through the toolbar's sort/filter controls. */
    private val _sort = MutableStateFlow(DownloadSortField.DATE_ADDED to false)
    private val _filter = MutableStateFlow(DownloadFilter.ALL)

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

    val state: StateFlow<DownloadsState> = combine(
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
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), DownloadsState(capabilities = capabilities))

    private val _effects = MutableSharedFlow<DownloadsEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        screenModelScope.launch { watchTransitions() }
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

            is DownloadsEvent.SortBy -> _sort.update { (field, ascending) ->
                if (field == event.field) field to !ascending
                // A freshly chosen column starts in its natural direction: newest/fastest first for
                // date and speed, A-z / smallest first for the rest.
                else event.field to (event.field != DownloadSortField.DATE_ADDED && event.field != DownloadSortField.SPEED)
            }

            is DownloadsEvent.FilterBy -> _filter.value = event.filter

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
    }
}
