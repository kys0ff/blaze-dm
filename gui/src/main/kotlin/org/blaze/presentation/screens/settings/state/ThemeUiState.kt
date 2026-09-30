package org.blaze.presentation.screens.settings.state

/**
 * UI-facing snapshot of an installed colour theme (built-in or plugin).
 *
 * [active] marks the currently selected theme; [isPlugin] controls whether the entry can be
 * removed from the settings screen. [configurable] is true when the theme publishes options,
 * which is what surfaces its "Options" editor button. [author] and [version] are the plugin's
 * self-declared metadata (blank when it declares none), shown as a byline under the theme name.
 */
data class ThemeUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val active: Boolean,
    val isPlugin: Boolean,
    val configurable: Boolean = false,
    val author: String = "",
    val version: String = "",
)
