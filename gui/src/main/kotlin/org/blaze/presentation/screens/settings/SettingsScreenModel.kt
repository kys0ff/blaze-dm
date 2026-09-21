package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.data.AppSettingsRepository
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.tray.api.TrayService
import org.blaze.presentation.screens.settings.state.HandlerUiState
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.screens.settings.state.ThemeUiState
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.ResolverSource
import org.blaze.theming.core.ThemeRegistry
import org.blaze.theming.core.ThemeSettingsRepository
import org.blaze.theming.core.ThemeSource
import java.nio.file.Path

class SettingsScreenModel(
    private val settingsRepository: EngineSettingsRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val autoStartCoordinator: AutoStartCoordinator,
    private val trayService: TrayService,
    private val resolverRegistry: LinkResolverRegistry,
    private val resolverSettingsRepository: LinkResolverSettingsRepository,
    private val themeRegistry: ThemeRegistry,
    private val themeSettingsRepository: ThemeSettingsRepository
) : ScreenModel {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    init {
        resetLocalState()
        screenModelScope.launch {
            combine(resolverRegistry.handlers, resolverSettingsRepository.settings) { handlers, settings ->
                HandlersSnapshot(
                    handlers = handlers.map { loaded ->
                        HandlerUiState(
                            id = loaded.resolver.id,
                            displayName = loaded.resolver.displayName,
                            description = loaded.resolver.description,
                            enabled = loaded.resolver.id !in settings.disabledHandlers,
                            isPlugin = loaded.source == ResolverSource.PLUGIN,
                            resolver = loaded
                        )
                    },
                    alwaysAsk = settings.alwaysAskHandler,
                    extensionDir = settings.extensionDir
                )
            }.collect { snapshot ->
                _state.update {
                    it.copy(
                        handlers = snapshot.handlers,
                        alwaysAskHandler = snapshot.alwaysAsk,
                        extensionDir = snapshot.extensionDir
                    )
                }
            }
        }
        screenModelScope.launch {
            combine(
                themeRegistry.themes,
                themeRegistry.selectedThemeId,
                themeSettingsRepository.settings
            ) { themes, selectedId, settings ->
                ThemesSnapshot(
                    themes = themes.map { loaded ->
                        ThemeUiState(
                            id = loaded.provider.id,
                            displayName = loaded.provider.displayName,
                            description = loaded.provider.description,
                            active = loaded.provider.id == selectedId,
                            isPlugin = loaded.source == ThemeSource.PLUGIN
                        )
                    },
                    themesDir = settings.extensionDir
                )
            }.collect { snapshot ->
                _state.update {
                    it.copy(themes = snapshot.themes, themesDir = snapshot.themesDir)
                }
            }
        }
    }

    private data class ThemesSnapshot(
        val themes: List<ThemeUiState>,
        val themesDir: String
    )

    private data class HandlersSnapshot(
        val handlers: List<HandlerUiState>,
        val alwaysAsk: Boolean,
        val extensionDir: String
    )

    private fun resetLocalState() {
        val currentSettings = settingsRepository.settings.value
        val currentAppSettings = appSettingsRepository.settings.value
        _state.update {
            SettingsState(
                currentCategory = it.currentCategory,
                settings = currentSettings,
                savedSettings = currentSettings,
                appSettings = currentAppSettings,
                savedAppSettings = currentAppSettings,
                traySupported = trayService.isSupported,
                autoStartSupported = autoStartCoordinator.isSupported,
                maxConcurrentDownloadsText = TextFieldValue(currentSettings.maxConcurrentDownloads.toString()),
                maxConnectionsPerDownloadText = TextFieldValue(currentSettings.maxConnectionsPerDownload.toString()),
                globalSpeedLimitKbpsText = TextFieldValue(currentSettings.globalSpeedLimitKbps.toString()),
                maxRetriesText = TextFieldValue(currentSettings.maxRetries.toString()),
                retryDelaySecondsText = TextFieldValue(currentSettings.retryDelaySeconds.toString()),
                maxRedirectsText = TextFieldValue(currentSettings.maxRedirects.toString()),
                maxPeerConnectionsText = TextFieldValue(currentSettings.maxPeerConnections.toString()),
                seedTimeLimitMinutesText = TextFieldValue(currentSettings.seedTimeLimitMinutes.toString()),
                userAgentText = TextFieldValue(currentSettings.httpUserAgent)
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
            is SettingsEvent.UpdateExponentialBackoff -> _state.update {
                it.copy(settings = it.settings.copy(exponentialBackoff = event.enabled))
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
            is SettingsEvent.UpdateUserAgent -> _state.update {
                it.copy(
                    userAgentText = event.value,
                    settings = it.settings.copy(httpUserAgent = event.value.text)
                )
            }
            is SettingsEvent.UpdateMaxRedirects -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 0) "Must be >= 0" else null
                _state.update {
                    it.copy(
                        maxRedirectsText = event.value,
                        maxRedirectsError = err,
                        settings = if (err == null) it.settings.copy(maxRedirects = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateMaxPeerConnections -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 1) "Must be >= 1" else null
                _state.update {
                    it.copy(
                        maxPeerConnectionsText = event.value,
                        maxPeerConnectionsError = err,
                        settings = if (err == null) it.settings.copy(maxPeerConnections = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateEnableSeeding -> _state.update {
                it.copy(settings = it.settings.copy(enableSeeding = event.enabled))
            }
            is SettingsEvent.UpdateSeedTimeLimitMinutes -> {
                val text = event.value.text
                val err = if (text.toIntOrNull() == null || text.toInt() < 1) "Must be >= 1" else null
                _state.update {
                    it.copy(
                        seedTimeLimitMinutesText = event.value,
                        seedTimeLimitMinutesError = err,
                        settings = if (err == null) it.settings.copy(seedTimeLimitMinutes = text.toInt()) else it.settings
                    )
                }
            }
            is SettingsEvent.UpdateThemeMode -> _state.update {
                it.copy(settings = it.settings.copy(themeMode = event.mode))
            }
            is SettingsEvent.UpdateResumeDownloadsOnStartup -> _state.update {
                it.copy(settings = it.settings.copy(resumeDownloadsOnStartup = event.enabled))
            }
            is SettingsEvent.UpdateStartQueuedOnStartup -> _state.update {
                it.copy(settings = it.settings.copy(startQueuedOnStartup = event.enabled))
            }
            is SettingsEvent.UpdateTrayEnabled -> _state.update {
                it.copy(appSettings = it.appSettings.copy(trayEnabled = event.enabled))
            }
            is SettingsEvent.UpdateMinimizeToTrayOnClose -> _state.update {
                it.copy(appSettings = it.appSettings.copy(minimizeToTrayOnClose = event.enabled))
            }
            is SettingsEvent.UpdateRunAtStartup -> _state.update {
                it.copy(appSettings = it.appSettings.copy(runAtStartup = event.enabled))
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
            is SettingsEvent.UpdateLogLevel -> _state.update {
                it.copy(settings = it.settings.copy(logLevel = event.level))
            }
            is SettingsEvent.UpdateFileLoggingEnabled -> _state.update {
                it.copy(settings = it.settings.copy(fileLoggingEnabled = event.enabled))
            }

            is SettingsEvent.ToggleHandler -> _state.update {
                it.copy(enabledHandlerOverrides = it.enabledHandlerOverrides + (event.id to event.enabled))
            }
            is SettingsEvent.UpdateAlwaysAskHandler -> _state.update {
                it.copy(alwaysAskOverride = event.enabled)
            }
            is SettingsEvent.InstallExtension -> screenModelScope.launch {
                resolverRegistry.install(Path.of(event.jarPath))
            }
            is SettingsEvent.RemoveExtension -> screenModelScope.launch {
                resolverRegistry.uninstall(event.id)
            }
            SettingsEvent.ReloadHandlers -> resolverRegistry.reload()

            is SettingsEvent.SelectTheme -> _state.update {
                it.copy(selectedThemeOverride = event.id)
            }
            is SettingsEvent.InstallTheme -> screenModelScope.launch {
                themeRegistry.install(Path.of(event.jarPath))
            }
            is SettingsEvent.RemoveTheme -> screenModelScope.launch {
                themeRegistry.uninstall(event.id)
            }
            SettingsEvent.ReloadThemes -> themeRegistry.reload()

            SettingsEvent.Apply -> applyDraftToLiveState()
            SettingsEvent.Ok -> persistDraftAndClose()
            SettingsEvent.Cancel -> screenModelScope.launch { _effects.emit(SettingsEffect.Close) }
        }
    }

    /**
     * Push the buffered draft into the repositories' / registries' live in-memory state so the
     * running app reflects it (restyle, logging level, limits...), WITHOUT writing to disk.
     * Dirty state is left intact on purpose: nothing has been persisted yet, so OK is still needed.
     */
    private fun applyDraftToLiveState() {
        val s = _state.value
        if (s.hasValidationError) return

        // Appearance mode, logging, download limits, ... (whole draft at once).
        settingsRepository.updateSettingsInMemory { s.settings }

        // Tray follows the in-memory app settings live; autostart has no in-memory-only
        // effect, so "live" here means actually writing/removing the OS registration.
        appSettingsRepository.updateSettingsInMemory { s.appSettings }
        autoStartCoordinator.applyLive(s.appSettings.runAtStartup)

        // Link-handler enable/disable and always-ask.
        if (s.enabledHandlerOverrides.isNotEmpty() || s.alwaysAskOverride != null) {
            resolverSettingsRepository.updateSettingsInMemory { settings ->
                val disabled = settings.disabledHandlers.toMutableSet()
                s.enabledHandlerOverrides.forEach { (id, enabled) ->
                    if (enabled) disabled.remove(id) else disabled.add(id)
                }
                settings.copy(
                    disabledHandlers = disabled.toList(),
                    alwaysAskHandler = s.alwaysAskOverride ?: settings.alwaysAskHandler
                )
            }
        }

        // Color theme selection.
        s.selectedThemeOverride
            ?.takeIf { id -> s.themes.any { it.id == id } }
            ?.let { themeRegistry.selectInMemory(it) }
    }

    /**
     * Write the buffered draft to disk (the only place persistence happens), then close via a
     * [SettingsEffect.Close].
     */
    private fun persistDraftAndClose() {
        val snapshot = _state.value
        if (snapshot.hasValidationError) return

        screenModelScope.launch {
            if (snapshot.settings != snapshot.savedSettings) {
                settingsRepository.updateSettings { snapshot.settings }
            }
            if (snapshot.appSettings != snapshot.savedAppSettings) {
                appSettingsRepository.updateSettings { snapshot.appSettings }
            }
            // Make sure the OS registration matches what was just persisted, even if the
            // user hit OK without Apply first (onDispose reverts to the persisted value).
            autoStartCoordinator.applyLive(snapshot.appSettings.runAtStartup)
            // Only replay overrides for handlers still installed (a removed one has no target).
            snapshot.enabledHandlerOverrides.forEach { (id, enabled) ->
                if (snapshot.handlers.any { it.id == id }) resolverRegistry.setEnabled(id, enabled)
            }
            snapshot.alwaysAskOverride?.let { resolverRegistry.setAlwaysAsk(it) }
            snapshot.selectedThemeOverride
                ?.takeIf { id -> snapshot.themes.any { it.id == id } }
                ?.let { themeRegistry.select(it) }

            val committed = snapshot.settings
            _state.update {
                it.copy(
                    savedSettings = committed,
                    savedAppSettings = snapshot.appSettings,
                    appSettings = snapshot.appSettings,
                    enabledHandlerOverrides = emptyMap(),
                    alwaysAskOverride = null,
                    selectedThemeOverride = null
                )
            }
            resetTextFieldsTo(committed)

            _effects.emit(SettingsEffect.Close)
        }
    }

    /**
     * Leaving the settings screen must never leave an unpersisted Apply in effect: revert the live
     * in-memory state back to the settings actually stored on disk. This runs for Cancel *and* for
     * navigating away (the model is disposed on pop/replaceAll); after OK it is a no-op because OK
     * already persisted the new values, so `revertToPersisted` restores exactly what was just saved.
     */
    override fun onDispose() {
        super.onDispose()
        settingsRepository.revertToPersisted()
        appSettingsRepository.revertToPersisted()
        autoStartCoordinator.revertToPersisted()
        resolverSettingsRepository.revertToPersisted()
        themeRegistry.restoreSelectionFromSettings()
    }

    /** Re-seed the numeric/text fields from [s] after a commit or reset. */
    private fun resetTextFieldsTo(s: DownloadSettings) {
        _state.update {
            it.copy(
                maxConcurrentDownloadsText = TextFieldValue(s.maxConcurrentDownloads.toString()),
                maxConnectionsPerDownloadText = TextFieldValue(s.maxConnectionsPerDownload.toString()),
                globalSpeedLimitKbpsText = TextFieldValue(s.globalSpeedLimitKbps.toString()),
                maxRetriesText = TextFieldValue(s.maxRetries.toString()),
                retryDelaySecondsText = TextFieldValue(s.retryDelaySeconds.toString()),
                maxRedirectsText = TextFieldValue(s.maxRedirects.toString()),
                maxPeerConnectionsText = TextFieldValue(s.maxPeerConnections.toString()),
                seedTimeLimitMinutesText = TextFieldValue(s.seedTimeLimitMinutes.toString()),
                userAgentText = TextFieldValue(s.httpUserAgent)
            )
        }
    }
}
