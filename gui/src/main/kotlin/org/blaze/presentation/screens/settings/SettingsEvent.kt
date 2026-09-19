package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.engine.settings.FileConflictBehavior

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
    
    // Startup
    data class UpdateResumeDownloadsOnStartup(val enabled: Boolean) : SettingsEvent
    data class UpdateStartQueuedOnStartup(val enabled: Boolean) : SettingsEvent
    
    // Destination & Conflicts
    data class UpdateDefaultDownloadDir(val path: String) : SettingsEvent
    data class UpdateAskWhereToSave(val enabled: Boolean) : SettingsEvent
    data class UpdateFileConflictBehavior(val behavior: FileConflictBehavior) : SettingsEvent
    
    data object SaveSettings : SettingsEvent
    data object ResetSettings : SettingsEvent
}
