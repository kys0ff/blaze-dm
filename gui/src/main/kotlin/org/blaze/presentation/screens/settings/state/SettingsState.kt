package org.blaze.presentation.screens.settings.state

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.engine.settings.DownloadSettings

data class SettingsState(
    val currentCategory: SettingsCategory = SettingsCategory.GENERAL,
    // `settings` is the in-memory draft the user edits; `savedSettings` is the last snapshot
    // committed to the repositories. Nothing is persisted until Apply/OK copies draft -> saved.
    val settings: DownloadSettings = DownloadSettings(),
    val savedSettings: DownloadSettings = DownloadSettings(),
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
    // Handler / theme state is loaded live from its registries, but user edits are buffered as
    // overrides here so they only reach the registry on Apply/OK (mirroring the draft settings).
    val handlers: List<HandlerUiState> = emptyList(),
    val enabledHandlerOverrides: Map<String, Boolean> = emptyMap(),
    val alwaysAskHandler: Boolean = true,
    val alwaysAskOverride: Boolean? = null,
    val extensionDir: String = "",
    val themes: List<ThemeUiState> = emptyList(),
    val selectedThemeOverride: String? = null,
    val themesDir: String = ""
) {
    /** Enabled state of a handler with any pending override folded in. */
    fun effectiveHandlerEnabled(handler: HandlerUiState): Boolean =
        enabledHandlerOverrides[handler.id] ?: handler.enabled

    /** Whether to always ask which handler to use, with any pending override folded in. */
    val effectiveAlwaysAsk: Boolean get() = alwaysAskOverride ?: alwaysAskHandler

    /** Whether a theme is selected, with any pending override folded in. */
    fun effectiveThemeActive(theme: ThemeUiState): Boolean =
        selectedThemeOverride?.let { it == theme.id } ?: theme.active

    /**
     * Errors on fields that are currently disabled don't count: the user can neither see nor
     * fix them, so they shouldn't block Apply/OK.
     */
    val hasValidationError: Boolean
        get() {
            val speedLimitEnabled = settings.globalSpeedLimitEnabled
            val autoRetryEnabled = settings.autoRetryFailed
            val seedingEnabled = settings.enableSeeding
            return maxConcurrentDownloadsError != null ||
                    maxConnectionsPerDownloadError != null ||
                    (speedLimitEnabled && globalSpeedLimitKbpsError != null) ||
                    (autoRetryEnabled && (maxRetriesError != null || retryDelaySecondsError != null)) ||
                    maxRedirectsError != null ||
                    maxPeerConnectionsError != null ||
                    (seedingEnabled && seedTimeLimitMinutesError != null)
        }

    /** True when there are uncommitted changes waiting to be applied. */
    val isDirty: Boolean
        get() = settings != savedSettings ||
                enabledHandlerOverrides.isNotEmpty() ||
                alwaysAskOverride != null ||
                selectedThemeOverride != null
}
