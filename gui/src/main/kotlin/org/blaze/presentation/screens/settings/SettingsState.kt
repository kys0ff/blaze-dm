package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.engine.settings.DownloadSettings

enum class SettingsCategory {
    GENERAL,
    DOWNLOADS,
    FILES
}

data class SettingsState(
    val currentCategory: SettingsCategory = SettingsCategory.GENERAL,
    val settings: DownloadSettings = DownloadSettings(),
    val maxConcurrentDownloadsText: TextFieldValue = TextFieldValue("4"),
    val maxConnectionsPerDownloadText: TextFieldValue = TextFieldValue("4"),
    val globalSpeedLimitKbpsText: TextFieldValue = TextFieldValue("10240"),
    val maxRetriesText: TextFieldValue = TextFieldValue("3"),
    val retryDelaySecondsText: TextFieldValue = TextFieldValue("5"),
    val maxConcurrentDownloadsError: String? = null,
    val maxConnectionsPerDownloadError: String? = null,
    val globalSpeedLimitKbpsError: String? = null,
    val maxRetriesError: String? = null,
    val retryDelaySecondsError: String? = null
)
