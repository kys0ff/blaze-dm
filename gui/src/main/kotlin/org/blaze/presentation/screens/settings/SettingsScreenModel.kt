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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.engine.settings.DownloadSettings
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.settings.state.SettingsState

/**
 * The Settings draft's state machine. It owns only presentation concerns - buffering the user's edits
 * into a [SettingsState] draft, validating text fields, and deciding when to apply, persist or discard -
 * while every read from and write to the repositories and plugin registries is delegated to
 * [SettingsUseCases].
 */
class SettingsScreenModel(
    private val useCases: SettingsUseCases,
    private val strings: BlazeStrings,
) : ScreenModel {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    init {
        resetLocalState()
        screenModelScope.launch {
            useCases.observeHandlers().collect { snapshot ->
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
            useCases.observeThemes().collect { snapshot ->
                _state.update {
                    it.copy(themes = snapshot.themes, themesDir = snapshot.themesDir)
                }
            }
        }
    }

    private fun resetLocalState() {
        val (currentSettings, currentAppSettings) = useCases.initialValues()
        val flags = useCases.supportFlags()
        _state.update {
            SettingsState(
                currentCategory = it.currentCategory,
                settings = currentSettings,
                savedSettings = currentSettings,
                appSettings = currentAppSettings,
                savedAppSettings = currentAppSettings,
                traySupported = flags.traySupported,
                autoStartSupported = flags.autoStartSupported,
                notificationsSupported = flags.notificationsSupported,
                maxConcurrentDownloadsText = TextFieldValue(currentSettings.maxConcurrentDownloads.toString()),
                maxConnectionsPerDownloadText = TextFieldValue(currentSettings.maxConnectionsPerDownload.toString()),
                globalSpeedLimitKbpsText = TextFieldValue(currentSettings.globalSpeedLimitKbps.toString()),
                snailSpeedKbpsText = TextFieldValue(currentSettings.snailSpeedKbps.toString()),
                maxRetriesText = TextFieldValue(currentSettings.maxRetries.toString()),
                retryDelaySecondsText = TextFieldValue(currentSettings.retryDelaySeconds.toString()),
                maxRedirectsText = TextFieldValue(currentSettings.maxRedirects.toString()),
                maxPeerConnectionsText = TextFieldValue(currentSettings.maxPeerConnections.toString()),
                seedTimeLimitMinutesText = TextFieldValue(currentSettings.seedTimeLimitMinutes.toString()),
                seedRatioLimitText = TextFieldValue(formatRatio(currentSettings.seedRatioLimit)),
                userAgentText = TextFieldValue(currentSettings.httpUserAgent)
            )
        }
    }

    fun onEvent(event: SettingsEvent) = when (event) {
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

        is SettingsEvent.UpdateDefaultDownloadPriority -> _state.update {
            it.copy(settings = it.settings.copy(defaultDownloadPriority = event.priority))
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

        is SettingsEvent.UpdateSnailModeEnabled -> _state.update {
            it.copy(settings = it.settings.copy(snailModeEnabled = event.enabled))
        }

        is SettingsEvent.UpdateSnailSpeedKbps -> {
            val text = event.value.text
            val err = if (text.toLongOrNull() == null || text.toLong() < 1) "Must be > 0" else null
            _state.update {
                it.copy(
                    snailSpeedKbpsText = event.value,
                    snailSpeedKbpsError = err,
                    settings = if (err == null) it.settings.copy(snailSpeedKbps = text.toLong()) else it.settings
                )
            }
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

        is SettingsEvent.UpdateUnlimitedSeeding -> _state.update {
            it.copy(settings = it.settings.copy(unlimitedSeeding = event.enabled))
        }

        is SettingsEvent.UpdateSeedRatioLimit -> {
            val text = event.value.text
            val ratio = text.toDoubleOrNull()
            val err =
                if (ratio == null || !ratio.isFinite() || ratio < 0.0) "Must be >= 0" else null
            _state.update {
                it.copy(
                    seedRatioLimitText = event.value,
                    seedRatioLimitError = err,
                    settings = if (err == null) it.settings.copy(seedRatioLimit = ratio!!) else it.settings
                )
            }
        }

        is SettingsEvent.UpdateThemeMode -> _state.update {
            it.copy(settings = it.settings.copy(themeMode = event.mode))
        }

        is SettingsEvent.UpdateLanguage -> _state.update {
            it.copy(appSettings = it.appSettings.copy(languageCode = event.code))
        }

        is SettingsEvent.UpdateAmbientGlowEnabled -> _state.update {
            it.copy(appSettings = it.appSettings.copy(ambientGlowEnabled = event.enabled))
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
            // Turning autostart off also drops its dependent "start minimized" sub-option, so a hidden
            // persisted flag can never keep minimizing manual launches behind an unchecked parent.
            it.copy(
                appSettings = it.appSettings.copy(
                    runAtStartup = event.enabled,
                    startMinimizedInTray = if (event.enabled) it.appSettings.startMinimizedInTray else false,
                )
            )
        }

        is SettingsEvent.UpdateStartMinimizedInTray -> _state.update {
            it.copy(appSettings = it.appSettings.copy(startMinimizedInTray = event.enabled))
        }

        is SettingsEvent.UpdateClipboardMonitoring -> _state.update {
            it.copy(appSettings = it.appSettings.copy(clipboardMonitoringEnabled = event.enabled))
        }

        is SettingsEvent.UpdateTorrentFilePreview -> _state.update {
            it.copy(appSettings = it.appSettings.copy(torrentFilePreviewEnabled = event.enabled))
        }

        is SettingsEvent.UpdateApproveExternalClients -> _state.update {
            it.copy(appSettings = it.appSettings.copy(approveExternalClients = event.enabled))
        }

        is SettingsEvent.UpdateApproveExtensions -> _state.update {
            it.copy(appSettings = it.appSettings.copy(approveExtensions = event.enabled))
        }

        is SettingsEvent.RevokeRememberedClient -> _state.update {
            it.copy(
                appSettings = it.appSettings.copy(
                    rememberedClients = it.appSettings.rememberedClients.filterNot { client -> client.id == event.clientId }
                )
            )
        }

        is SettingsEvent.ForgetTrustedExtension -> _state.update {
            it.copy(
                appSettings = it.appSettings.copy(
                    trustedExtensions = it.appSettings.trustedExtensions.filterNot { extension ->
                        extension.sha256 == event.sha256 && extension.kind == event.kind
                    }
                )
            )
        }

        is SettingsEvent.UpdateDesktopNotificationsEnabled -> _state.update {
            it.copy(appSettings = it.appSettings.copy(desktopNotificationsEnabled = event.enabled))
        }

        is SettingsEvent.UpdateNotifyOnCompleted -> _state.update {
            it.copy(appSettings = it.appSettings.copy(notifyOnCompleted = event.enabled))
        }

        is SettingsEvent.UpdateNotifyOnFailed -> _state.update {
            it.copy(appSettings = it.appSettings.copy(notifyOnFailed = event.enabled))
        }

        is SettingsEvent.UpdateNotifyOnRestarted -> _state.update {
            it.copy(appSettings = it.appSettings.copy(notifyOnRestarted = event.enabled))
        }

        is SettingsEvent.UpdateNotifyWhileInSight -> _state.update {
            it.copy(appSettings = it.appSettings.copy(notifyWhileWindowInSight = event.enabled))
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

        // Keyboard shortcuts. Conflicts and unsafe chords are derived from the draft in SettingsState,
        // so editing these maps is all that is needed — validation and isDirty follow automatically.
        is SettingsEvent.SetKeyBinding -> _state.update {
            it.copy(
                appSettings = it.appSettings.copy(
                    keyBindings = it.appSettings.keyBindings + (event.actionName to event.binding),
                    unboundShortcuts = it.appSettings.unboundShortcuts.filterNot { name -> name == event.actionName }
                )
            )
        }

        is SettingsEvent.ClearKeyBinding -> _state.update {
            it.copy(
                appSettings = it.appSettings.copy(
                    keyBindings = it.appSettings.keyBindings - event.actionName,
                    unboundShortcuts = (it.appSettings.unboundShortcuts + event.actionName).distinct()
                )
            )
        }

        is SettingsEvent.RestoreDefaultKeyBinding -> _state.update {
            it.copy(
                appSettings = it.appSettings.copy(
                    keyBindings = it.appSettings.keyBindings - event.actionName,
                    unboundShortcuts = it.appSettings.unboundShortcuts.filterNot { name -> name == event.actionName }
                )
            )
        }

        SettingsEvent.RestoreAllKeyBindings -> _state.update {
            it.copy(
                appSettings = it.appSettings.copy(
                    keyBindings = emptyMap(),
                    unboundShortcuts = emptyList()
                )
            )
        }

        is SettingsEvent.ToggleHandler -> _state.update {
            it.copy(enabledHandlerOverrides = it.enabledHandlerOverrides + (event.id to event.enabled))
        }

        is SettingsEvent.UpdateHandlerOptions -> _state.update {
            it.copy(optionOverrides = it.optionOverrides + (event.id to event.values))
        }

        is SettingsEvent.UpdateAlwaysAskHandler -> _state.update {
            it.copy(alwaysAskOverride = event.enabled)
        }

        is SettingsEvent.InstallExtension -> screenModelScope.launch {
            // The use case answers null on success, DENIED_INSTALL when the consent dialog was
            // refused, and an error message on a real failure; either way the user hears back.
            reportInstall(useCases.installHandler(event.jarPath))
        }

        is SettingsEvent.RemoveExtension -> screenModelScope.launch {
            useCases.uninstallHandler(event.id)
        }

        SettingsEvent.ReloadHandlers -> useCases.reloadHandlers()

        is SettingsEvent.SelectTheme -> _state.update {
            it.copy(selectedThemeOverride = event.id)
        }

        is SettingsEvent.UpdateThemeOptions -> _state.update {
            it.copy(themeOptionOverrides = it.themeOptionOverrides + (event.id to event.values))
        }

        is SettingsEvent.InstallTheme -> screenModelScope.launch {
            reportInstall(useCases.installTheme(event.jarPath))
        }

        is SettingsEvent.RemoveTheme -> screenModelScope.launch {
            useCases.uninstallTheme(event.id)
        }

        SettingsEvent.ReloadThemes -> useCases.reloadThemes()

        SettingsEvent.Apply -> applyDraftToLiveState()
        SettingsEvent.Ok -> persistDraftAndClose()
        SettingsEvent.Cancel -> screenModelScope.launch { _effects.emit(SettingsEffect.Close) }
    }

    /**
     * Push the buffered draft into the repositories' / registries' live in-memory state via
     * [SettingsUseCases.applyLive]. Dirty state is left intact on purpose: nothing has been persisted
     * yet, so OK is still needed.
     */
    private fun applyDraftToLiveState() {
        val s = _state.value
        if (s.hasValidationError) return
        useCases.applyLive(s.toDraft())
    }

    /**
     * Write the buffered draft to disk with [SettingsUseCases.persist], clear the buffered overrides,
     * re-seed the text fields and close via a [SettingsEffect.Close].
     */
    private fun persistDraftAndClose() {
        val snapshot = _state.value
        if (snapshot.hasValidationError) return

        screenModelScope.launch {
            useCases.persist(snapshot.toDraft())

            val committed = snapshot.settings
            _state.update {
                it.copy(
                    savedSettings = committed,
                    savedAppSettings = snapshot.appSettings,
                    appSettings = snapshot.appSettings,
                    enabledHandlerOverrides = emptyMap(),
                    optionOverrides = emptyMap(),
                    alwaysAskOverride = null,
                    selectedThemeOverride = null,
                    themeOptionOverrides = emptyMap()
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
        useCases.revertToPersisted()
    }

    /** Projects the presentation draft onto the subset of fields the data layer acts on. */
    private fun SettingsState.toDraft() = SettingsDraft(
        settings = settings,
        savedSettings = savedSettings,
        appSettings = appSettings,
        savedAppSettings = savedAppSettings,
        enabledHandlerOverrides = enabledHandlerOverrides,
        optionOverrides = optionOverrides,
        alwaysAskOverride = alwaysAskOverride,
        installedHandlerIds = handlers.mapTo(HashSet()) { it.id },
        themeIds = themes.mapTo(HashSet()) { it.id },
        selectedThemeOverride = selectedThemeOverride,
        themeOptionOverrides = themeOptionOverrides,
    )

    /**
     * Turn an install result (see [SettingsUseCases.installHandler]) into one line the screen shows:
     * nothing on success, the consent-refusal copy when the user declined, the error otherwise.
     */
    private suspend fun reportInstall(error: String?) {
        when (error) {
            null -> Unit
            SettingsUseCases.DENIED_INSTALL -> _effects.emit(SettingsEffect.Message(strings.settings.handlers.installRefused))
            else -> _effects.emit(SettingsEffect.Message(strings.settings.handlers.installFailed(error)))
        }
    }

    /** Re-seed the numeric/text fields from [s] after a commit or reset. */
    private fun resetTextFieldsTo(s: DownloadSettings) {
        _state.update {
            it.copy(
                maxConcurrentDownloadsText = TextFieldValue(s.maxConcurrentDownloads.toString()),
                maxConnectionsPerDownloadText = TextFieldValue(s.maxConnectionsPerDownload.toString()),
                globalSpeedLimitKbpsText = TextFieldValue(s.globalSpeedLimitKbps.toString()),
                snailSpeedKbpsText = TextFieldValue(s.snailSpeedKbps.toString()),
                maxRetriesText = TextFieldValue(s.maxRetries.toString()),
                retryDelaySecondsText = TextFieldValue(s.retryDelaySeconds.toString()),
                maxRedirectsText = TextFieldValue(s.maxRedirects.toString()),
                maxPeerConnectionsText = TextFieldValue(s.maxPeerConnections.toString()),
                seedTimeLimitMinutesText = TextFieldValue(s.seedTimeLimitMinutes.toString()),
                seedRatioLimitText = TextFieldValue(formatRatio(s.seedRatioLimit)),
                userAgentText = TextFieldValue(s.httpUserAgent)
            )
        }
    }

    /** Renders a share ratio without a trailing \".0\" (1.0 -> \"1\", 0.5 -> \"0.5\"). */
    private fun formatRatio(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
