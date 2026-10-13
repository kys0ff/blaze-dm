package org.blaze.presentation.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import org.blaze.data.AppSettingsRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.ThemeMode
import org.island.theme.core.ThemeRegistry
import org.island.theme.ui.IslandTheme
import org.koin.compose.koinInject

/**
 * Application theme: resolves the configured [ThemeMode] (light/dark/system) and the
 * active color theme (built-in + plugin, via [ThemeRegistry]) into a [IslandTheme].
 *
 * Observing the settings and registry flows makes the palette recompute when the
 * selection changes or a theme jar is installed / removed. The app-shell settings are
 * observed too, so the font customizations (interface family, monospace family and text
 * size) reach the theme on Apply/OK — and revert with the rest of the shell draft.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val settingsRepository = koinInject<EngineSettingsRepository>()
    val themeMode by settingsRepository.settings
        .map { it.themeMode }
        .collectAsState(initial = settingsRepository.settings.value.themeMode)
    val systemIsDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val themeRegistry = koinInject<ThemeRegistry>()
    val selectedThemeId by themeRegistry.selectedThemeId.collectAsState()
    val themes by themeRegistry.themes.collectAsState()
    // Also observe the persisted+live settings so a per-theme option edit (a color the user picked
    // in a theme's Options form) recomputes the palette on Apply, not just a change of selection.
    val themeSettings by themeRegistry.settings.collectAsState()
    val palette = remember(selectedThemeId, themes, themeSettings, isDark) {
        themeRegistry.paletteFor(isDark)
    }

    // Live font customization values from the app-shell repository — the same flow the Settings
    // draft commits through on Apply/OK, so the whole app re-faces without any extra plumbing.
    val appSettings by koinInject<AppSettingsRepository>().settings.collectAsState()

    IslandTheme(
        palette = palette,
        isDark = isDark,
        uiFontFamilyId = appSettings.uiFontFamilyId,
        monoFontFamilyId = appSettings.monoFontFamilyId,
        uiTextScalePercent = appSettings.uiTextScalePercent,
        content = content,
    )
}
