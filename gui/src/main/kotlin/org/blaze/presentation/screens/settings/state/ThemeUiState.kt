package org.blaze.presentation.screens.settings.state

/**
 * UI-facing snapshot of an installed colour theme (built-in or plugin).
 *
 * [active] marks the currently selected theme; [isPlugin] controls whether the entry can be
 * removed from the settings screen.
 */
data class ThemeUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val active: Boolean,
    val isPlugin: Boolean
)
