package org.blaze.presentation.screens.downloads

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.blaze.domain.models.Download
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
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.files.SystemFileService
import java.nio.file.Path

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
    private val pauseAllDownloads: PauseAllDownloadsUseCase,
    private val resumeAllDownloads: ResumeAllDownloadsUseCase,
    private val clearCompletedDownloads: ClearCompletedDownloadsUseCase,
    private val detectPortableDownload: DetectPortableDownloadUseCase,
    private val importPortableDownload: ImportPortableDownloadUseCase,
    private val systemFileService: SystemFileService,
    private val clipboard: SystemClipboard
) : ScreenModel {

    private val _searchQuery = MutableStateFlow("")

    /** Capabilities are fixed for the host; resolved once so the row menu can hide unsupported actions. */
    private val capabilities = DownloadCapabilities(
        canOpenFiles = systemFileService.canOpenFiles,
        canRevealInFolder = systemFileService.canRevealInFolder,
        canBrowseLinks = systemFileService.canBrowseLinks,
        canCopy = clipboard.isSupported
    )

    val state: StateFlow<DownloadsState> = combine(
        getDownloads(),
        _searchQuery,
        settingsRepository.settings
    ) { downloads, query, settings ->
        DownloadsState(
            downloads = downloads,
            searchQuery = query,
            maxConcurrentDownloads = settings.maxConcurrentDownloads,
            capabilities = capabilities,
            filteredDownloads = if (query.isBlank()) {
                downloads
            } else {
                downloads.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.url.contains(query, ignoreCase = true)
                }
            }
        )
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), DownloadsState(capabilities = capabilities))

    private val _effects = MutableSharedFlow<DownloadsEffect>()
    val effects = _effects.asSharedFlow()

    fun onEvent(event: DownloadsEvent) = when (event) {
        is DownloadsEvent.SearchChanged -> _searchQuery.value = event.query
        is DownloadsEvent.AddDownload -> screenModelScope.launch {
            try {
                addDownload(
                    url = event.url,
                    savePath = event.savePath,
                    name = event.name,
                    fileIndices = event.fileIndices,
                    totalSize = event.totalSize,
                    files = event.files,
                    scheduledAt = event.scheduledAt
                )
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to add download: ${e.message}"))
            }
        }

        is DownloadsEvent.Pause -> screenModelScope.launch {
            try {
                pauseDownload(event.id)
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to pause download: ${e.message}"))
            }
        }

        is DownloadsEvent.Resume -> screenModelScope.launch {
            try {
                resumeDownload(event.id)
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to resume download: ${e.message}"))
            }
        }

        is DownloadsEvent.Remove -> screenModelScope.launch {
            try {
                removeDownload(
                    id = event.id,
                    deleteFile = event.deleteFile
                )
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to remove download: ${e.message}"))
            }
        }

        is DownloadsEvent.Retry -> screenModelScope.launch {
            try {
                retryDownload(event.id)
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to retry download: ${e.message}"))
            }
        }

        is DownloadsEvent.Cancel -> screenModelScope.launch {
            try {
                cancelDownload(event.id)
                _effects.emit(DownloadsEffect.ShowMessage("Download canceled"))
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to cancel download: ${e.message}"))
            }
        }

        DownloadsEvent.PauseAll -> screenModelScope.launch {
            try {
                pauseAllDownloads()
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to pause all downloads: ${e.message}"))
            }
        }

        DownloadsEvent.ResumeAll -> screenModelScope.launch {
            try {
                resumeAllDownloads()
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to resume all downloads: ${e.message}"))
            }
        }

        DownloadsEvent.ClearCompleted -> screenModelScope.launch {
            try {
                clearCompletedDownloads()
            } catch (e: Exception) {
                _effects.emit(DownloadsEffect.ShowError("Failed to clear completed downloads: ${e.message}"))
            }
        }

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
}
