package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.blaze.engine.api.DownloadEngine
import org.blaze.tray.api.TrayService
import org.koin.compose.koinInject

/**
 * Owns the app-window lifecycle: geometry, visibility, and shutdown.
 *
 * `windowVisible` drives the AWT frame while the tray hides the window instead of
 * quitting; the internal `exiting` flag marks the real shutdown so the close handler
 * and the tray's Quit don't loop back into "hide".
 */
class AppShell internal constructor(
    val windowState: WindowState,
    val windowVisible: MutableState<Boolean>,
    private val appSettingsRepository: AppSettingsRepository,
    private val trayService: TrayService,
    private val engine: DownloadEngine,
    private val exitApplication: () -> Unit,
) {
    /** Whether the app is in its real shutdown path (as opposed to hidden to the tray). */
    private var exiting = false

    /** Minimize-to-tray can only work when the tray is both enabled and supported. */
    private val trayActive: Boolean
        get() = appSettings.trayEnabled && trayService.isSupported

    private val appSettings
        get() = appSettingsRepository.settings.value

    /** Hide the window into the tray (or quit, when the tray can't hold the app). */
    fun onCloseRequest() {
        if (trayActive && appSettings.minimizeToTrayOnClose && !exiting) {
            hide()
        } else {
            quit()
        }
    }

    /** Fold a title-bar minimize into the single hide-to-tray model. */
    fun onMinimize() = hide()

    /** Shut the download engine down cleanly and exit the Compose application. */
    fun quit() {
        exiting = true
        runBlocking {
            engine.shutdown()
        }
        exitApplication()
    }

    private fun hide() {
        windowVisible.value = false
    }
}

/**
 * Composition-scoped factory for [AppShell]; wires the Koin collaborators and the
 * Compose window state into it. The returned instance is stable across recompositions.
 */
@Composable
fun ApplicationScope.rememberAppShell(): AppShell {
    val windowState = rememberWindowState(
        size = DpSize(1100.dp, 720.dp),
    )
    val windowVisible = remember { mutableStateOf(true) }
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    val trayService = koinInject<TrayService>()
    val engine = koinInject<DownloadEngine>()

    return remember(windowState, windowVisible) {
        AppShell(
            windowState = windowState,
            windowVisible = windowVisible,
            appSettingsRepository = appSettingsRepository,
            trayService = trayService,
            engine = engine,
            exitApplication = ::exitApplication,
        )
    }
}
