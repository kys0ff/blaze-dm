package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.engine.settings.ThemeMode

sealed interface SettingsEvent {
    data class ChangeCategory(val category: SettingsCategory) : SettingsEvent
    
    // Concurrency & Queueing
    data class UpdateMaxConcurrentDownloads(val value: TextFieldValue) : SettingsEvent
    data class UpdateMaxConnectionsPerDownload(val value: TextFieldValue) : SettingsEvent
    
    // Bandwidth
    data class UpdateSpeedLimitEnabled(val enabled: Boolean) : SettingsEvent
    data class UpdateSpeedLimitKbps(val value: TextFieldValue) : SettingsEvent
    
    // Retries
    data class UpdateAutoRetryFailed(val enabled: Boolean) : SettingsEvent
    data class UpdateMaxRetries(val value: TextFieldValue) : SettingsEvent
    data class UpdateRetryDelaySeconds(val value: TextFieldValue) : SettingsEvent
    data class UpdateExponentialBackoff(val enabled: Boolean) : SettingsEvent

    // Network & Protocol
    data class UpdateUserAgent(val value: TextFieldValue) : SettingsEvent
    data class UpdateMaxRedirects(val value: TextFieldValue) : SettingsEvent

    // Torrent
    data class UpdateMaxPeerConnections(val value: TextFieldValue) : SettingsEvent
    data class UpdateEnableSeeding(val enabled: Boolean) : SettingsEvent
    data class UpdateSeedTimeLimitMinutes(val value: TextFieldValue) : SettingsEvent

    // Appearance
    data class UpdateThemeMode(val mode: ThemeMode) : SettingsEvent
    
    // Startup
    data class UpdateResumeDownloadsOnStartup(val enabled: Boolean) : SettingsEvent
    data class UpdateStartQueuedOnStartup(val enabled: Boolean) : SettingsEvent
    
    // Destination & Conflicts
    data class UpdateDefaultDownloadDir(val path: String) : SettingsEvent
    data class UpdateAskWhereToSave(val enabled: Boolean) : SettingsEvent
    data class UpdateFileConflictBehavior(val behavior: FileConflictBehavior) : SettingsEvent

    // Link handlers / extensions
    data class ToggleHandler(val id: String, val enabled: Boolean) : SettingsEvent
    data class UpdateAlwaysAskHandler(val enabled: Boolean) : SettingsEvent
    data class InstallExtension(val jarPath: String) : SettingsEvent
    data class RemoveExtension(val id: String) : SettingsEvent
    data object ReloadHandlers : SettingsEvent

    data object SaveSettings : SettingsEvent
    data object ResetSettings : SettingsEvent
}
