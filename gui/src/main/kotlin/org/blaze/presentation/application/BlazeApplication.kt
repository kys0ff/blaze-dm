package org.blaze.presentation.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.blaze.domain.repository.DownloadRepository
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.ThemeMode
import org.blaze.i18n.LocalBlazeStrings
import org.blaze.i18n.LocaleManager
import org.blaze.i18n.getStrings
import org.blaze.platform.tray.AppTrayIcon
import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayMenuItem
import org.blaze.tray.api.TrayService
import org.blaze.tray.compose.TrayHost
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

    // App-shell (tray) state: `windowVisible` drives the AWT frame while the tray hides the
    // window instead of quitting; `exiting` marks the real shutdown so the close handler and
    // the tray's Quit don't loop back into "hide".
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    val appSettings by appSettingsRepository.settings.collectAsState()
    val trayService = koinInject<TrayService>()
    val downloadRepository = koinInject<DownloadRepository>()
    val appScope = koinInject<CoroutineScope>()

    var windowVisible by remember { mutableStateOf(true) }
    var exiting by remember { mutableStateOf(false) }
    val trayActive = appSettings.trayEnabled && trayService.isSupported

    fun quitApp() {
        exiting = true
        runBlocking {
            engine.shutdown()
        }
        exitApplication()
    }

    // Blaze's tray identity, artwork and menu; the :tray library stays app-agnostic.
    val trayConfig = remember(strings) {
        TrayConfig(
            id = "org.blaze.Downloader",
            title = "Blaze",
            icon = { size -> AppTrayIcon.image(size) },
            menu = listOf(
                TrayMenuItem.WindowToggle(
                    hiddenLabel = strings.tray.show,
                    shownLabel = strings.tray.hide,
                ) { windowVisible = !windowVisible },
                TrayMenuItem.Item(strings.tray.pauseAll) {
                    appScope.launch { downloadRepository.pauseAll() }
                },
                TrayMenuItem.Item(strings.tray.resumeAll) {
                    appScope.launch { downloadRepository.resumeAll() }
                },
                TrayMenuItem.Separator,
                TrayMenuItem.Item(strings.tray.quit) { quitApp() },
            ),
        )
    }

    // Tray lifecycle (install on setting/locale change, Show/Hide sync, removal on
    // teardown) is owned by the :tray module's Compose wrapper.
    TrayHost(
        service = trayService,
        enabled = appSettings.trayEnabled,
        config = trayConfig,
        windowVisible = windowVisible,
    )

    CompositionLocalProvider(LocalBlazeStrings provides strings) {
        BlazeTheme(palette = palette, isDark = isDark) {
            BlazeWindow(
                windowState = windowState,
                visible = windowVisible,
                onCloseRequest = {
                    if (trayActive && appSettings.minimizeToTrayOnClose && !exiting) {
                        // TrayHost propagates the new visibility into the Show/Hide item.
                        windowVisible = false
                    } else {
                        quitApp()
                    }
                },
            )
        }
    }
}
