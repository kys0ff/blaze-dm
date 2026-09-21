package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.engine.settings.DownloadSettings

enum class SettingsCategory {
    GENERAL,
    DOWNLOADS,
    FILES,
    EXTENSIONS
}

/** UI-facing snapshot of a registered link handler. */
data class HandlerUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val isPlugin: Boolean
)

data class SettingsState(
    val currentCategory: SettingsCategory = SettingsCategory.GENERAL,
    val settings: DownloadSettings = DownloadSettings(),
    val maxConcurrentDownloadsText: TextFieldValue = TextFieldValue("4"),
    val maxConnectionsPerDownloadText: TextFieldValue = TextFieldValue("4"),
    val globalSpeedLimitKbpsText: TextFieldValue = TextFieldValue("10240"),
    val maxRetriesText: TextFieldValue = TextFieldValue("3"),
    val retryDelaySecondsText: TextFieldValue = TextFieldValue("5"),
    val maxRedirectsText: TextFieldValue = TextFieldValue("5"),
    val maxPeerConnectionsText: TextFieldValue = TextFieldValue("200"),
    val seedTimeLimitMinutesText: TextFieldValue = TextFieldValue("30"),
    val userAgentText: TextFieldValue = TextFieldValue(""),
    val maxConcurrentDownloadsError: String? = null,
    val maxConnectionsPerDownloadError: String? = null,
    val globalSpeedLimitKbpsError: String? = null,
    val maxRetriesError: String? = null,
    val retryDelaySecondsError: String? = null,
    val maxRedirectsError: String? = null,
    val maxPeerConnectionsError: String? = null,
    val seedTimeLimitMinutesError: String? = null,
    val handlers: List<HandlerUiState> = emptyList(),
    val alwaysAskHandler: Boolean = true,
    val extensionDir: String = ""
)
