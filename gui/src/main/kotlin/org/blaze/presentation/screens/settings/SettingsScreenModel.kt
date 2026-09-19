package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.engine.settings.EngineSettingsRepository

class SettingsScreenModel(
    private val settingsRepository: EngineSettingsRepository
) : ScreenModel {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        resetLocalState()
    }

    private fun resetLocalState() {
        val currentSettings = settingsRepository.settings.value
        _state.update {
            SettingsState(
                currentCategory = it.currentCategory,
                settings = currentSettings,
                maxConcurrentDownloadsText = TextFieldValue(currentSettings.maxConcurrentDownloads.toString()),
                maxConnectionsPerDownloadText = TextFieldValue(currentSettings.maxConnectionsPerDownload.toString()),
                globalSpeedLimitKbpsText = TextFieldValue(currentSettings.globalSpeedLimitKbps.toString()),
                maxRetriesText = TextFieldValue(currentSettings.maxRetries.toString()),
                retryDelaySecondsText = TextFieldValue(currentSettings.retryDelaySeconds.toString())
            )
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ChangeCategory -> _state.update { it.copy(currentCategory = event.category) }
            
            is SettingsEvent.UpdateMaxConcurrentDownloads -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 1) "Must be >= 1" else null
                _state.update {
                    it.copy(
                        maxConcurrentDownloadsText = event.value,
                        maxConcurrentDownloadsError = err,
                        settings = if (err == null) it.settings.copy(maxConcurrentDownloads = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateMaxConnectionsPerDownload -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 1) "Must be >= 1" else null
                _state.update {
                    it.copy(
                        maxConnectionsPerDownloadText = event.value,
                        maxConnectionsPerDownloadError = err,
                        settings = if (err == null) it.settings.copy(maxConnectionsPerDownload = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateSpeedLimitEnabled -> _state.update {
                it.copy(settings = it.settings.copy(globalSpeedLimitEnabled = event.enabled))
            }
            is SettingsEvent.UpdateSpeedLimitKbps -> {
                val text = event.value.text
                val err = if (text.toLongOrNull() == null || text.toLong() < 1) "Must be > 0" else null
                _state.update {
                    it.copy(
                        globalSpeedLimitKbpsText = event.value,
                        globalSpeedLimitKbpsError = err,
                        settings = if (err == null) it.settings.copy(globalSpeedLimitKbps = text.toLong()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateAutoRetryFailed -> _state.update {
                it.copy(settings = it.settings.copy(autoRetryFailed = event.enabled))
            }
            is SettingsEvent.UpdateMaxRetries -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 0) "Must be >= 0" else null
                _state.update {
                    it.copy(
                        maxRetriesText = event.value,
                        maxRetriesError = err,
                        settings = if (err == null) it.settings.copy(maxRetries = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateRetryDelaySeconds -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 0) "Must be >= 0" else null
                _state.update {
                    it.copy(
                        retryDelaySecondsText = event.value,
                        retryDelaySecondsError = err,
                        settings = if (err == null) it.settings.copy(retryDelaySeconds = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateResumeDownloadsOnStartup -> _state.update {
                it.copy(settings = it.settings.copy(resumeDownloadsOnStartup = event.enabled))
            }
            is SettingsEvent.UpdateStartQueuedOnStartup -> _state.update {
                it.copy(settings = it.settings.copy(startQueuedOnStartup = event.enabled))
            }
            is SettingsEvent.UpdateDefaultDownloadDir -> _state.update {
                it.copy(settings = it.settings.copy(defaultDownloadDir = event.path))
            }
            is SettingsEvent.UpdateAskWhereToSave -> _state.update {
                it.copy(settings = it.settings.copy(askWhereToSave = event.enabled))
            }
            is SettingsEvent.UpdateFileConflictBehavior -> _state.update {
                it.copy(settings = it.settings.copy(fileConflictBehavior = event.behavior))
            }
            SettingsEvent.SaveSettings -> {
                val s = _state.value
                if (s.maxConcurrentDownloadsError == null &&
                    s.maxConnectionsPerDownloadError == null &&
                    s.globalSpeedLimitKbpsError == null &&
                    s.maxRetriesError == null &&
                    s.retryDelaySecondsError == null
                ) {
                    screenModelScope.launch {
                        settingsRepository.updateSettings { s.settings }
                    }
                }
            }
            SettingsEvent.ResetSettings -> resetLocalState()
        }
    }
}
