package org.blaze.presentation.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.ThemeMode
import org.blaze.presentation.theme.BlazeTheme
import org.blaze.theming.core.ThemeRegistry
import org.koin.compose.koinInject

/**
 * Application theme: resolves the configured [ThemeMode] (light/dark/system) and the
 * active color theme (built-in + plugin, via [ThemeRegistry]) into a [BlazeTheme].
 *
 * Observing the settings and registry flows makes the palette recompute when the
 * selection changes or a theme jar is installed / removed.
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
    val palette = remember(selectedThemeId, themes, isDark) {
        themeRegistry.paletteFor(isDark)
    }

    BlazeTheme(palette = palette, isDark = isDark, content = content)
}
