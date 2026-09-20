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
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.usecase.AddDownloadUseCase
import org.blaze.domain.usecase.CancelDownloadUseCase
import org.blaze.domain.usecase.ClearCompletedDownloadsUseCase
import org.blaze.domain.usecase.FetchMetadataUseCase
import org.blaze.domain.usecase.GetDestinationPathUseCase
import org.blaze.domain.usecase.GetDownloadsUseCase
import org.blaze.domain.usecase.PauseAllDownloadsUseCase
import org.blaze.domain.usecase.PauseDownloadUseCase
import org.blaze.domain.usecase.RemoveDownloadUseCase
import org.blaze.domain.usecase.ResumeAllDownloadsUseCase
import org.blaze.domain.usecase.ResumeDownloadUseCase
import org.blaze.domain.usecase.RetryDownloadUseCase
import org.blaze.engine.settings.EngineSettingsRepository

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
    private val clearCompletedDownloads: ClearCompletedDownloadsUseCase
) : ScreenModel {

    private val _searchQuery = MutableStateFlow("")

    val state: StateFlow<DownloadsState> = combine(
        getDownloads(),
        _searchQuery,
        settingsRepository.settings
    ) { downloads, query, settings ->
        DownloadsState(
            downloads = downloads,
            searchQuery = query,
            maxConcurrentDownloads = settings.maxConcurrentDownloads,
            filteredDownloads = if (query.isBlank()) {
                downloads
            } else {
                downloads.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.url.contains(query, ignoreCase = true)
                }
            }
        )
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), DownloadsState())

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
                    files = event.files
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
    }

    suspend fun fetchMetadata(url: String): DownloadMetadata? = fetchMetadataUseCase(url)

    suspend fun resolveDestinationPath(url: String, savePath: String, name: String?): String =
        getDestinationPath(url, savePath, name)
}
