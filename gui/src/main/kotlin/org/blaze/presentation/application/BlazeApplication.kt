package org.blaze.presentation.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.ThemeMode
import org.blaze.i18n.LocalBlazeStrings
import org.blaze.i18n.LocaleManager
import org.blaze.i18n.getStrings
import org.blaze.presentation.application.components.BlazeWindow
import org.blaze.presentation.theme.BlazeTheme
import org.blaze.theming.core.ThemeRegistry
import org.koin.compose.koinInject

@Composable
fun ApplicationScope.BlazeApplication() {
    val localeManager = koinInject<LocaleManager>()
    val currentLocale by localeManager.currentLocale.collectAsState()
    val strings = remember(currentLocale) { getStrings(currentLocale) }

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

    // Resolve the active colour theme (built-in + plugin) for the current light/dark mode.
    // Observing both flows makes the palette recompute when the selection changes or a
    // theme jar is installed / removed.
    val themeRegistry = koinInject<ThemeRegistry>()
    val selectedThemeId by themeRegistry.selectedThemeId.collectAsState()
    val themes by themeRegistry.themes.collectAsState()
    val palette = remember(selectedThemeId, themes, isDark) {
        themeRegistry.paletteFor(isDark)
    }

    val windowState = rememberWindowState(
        size = DpSize(1100.dp, 720.dp),
    )

    val engine = koinInject<DownloadEngine>()

    CompositionLocalProvider(LocalBlazeStrings provides strings) {
        BlazeTheme(palette = palette, isDark = isDark) {
            BlazeWindow(
                windowState = windowState,
                onCloseRequest = {
                    runBlocking {
                        engine.shutdown()
                    }
                    exitApplication()
                },
            )
        }
    }
}
