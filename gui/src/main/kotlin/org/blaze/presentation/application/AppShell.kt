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
import org.blaze.download.api.DownloadsApi
import org.blaze.tray.api.TrayService
import org.koin.compose.koinInject

/**
 * Owns the app-window lifecycle: geometry, visibility, the modal Settings window, and shutdown.
 *
 * `windowVisible` drives the AWT frame while the tray hides the window instead of
 * quitting; the internal `exiting` flag marks the real shutdown so the close handler
 * and the tray's Quit don't loop back into "hide".
 *
 * [settingsOpen] is shell state rather than screen state because the Settings window is a real
 * window: it has to be reachable when no screen is in a position to open it (the tray, an external
 * open, the title-bar menu) and it has to be torn down when the frame itself goes away.
 */
class AppShell internal constructor(
    val windowState: WindowState,
    val windowVisible: MutableState<Boolean>,
    val windowRaiseToken: MutableState<Long>,
    val settingsOpen: MutableState<Boolean>,
    private val appSettingsRepository: AppSettingsRepository,
    private val trayService: TrayService,
    private val downloadsApi: DownloadsApi,
    private val exitApplication: () -> Unit,
) {
    /** Whether the app is in its real shutdown path (as opposed to hidden to the tray). */
    private var exiting = false

    /**
     * Surface the window for an external open / Dock reopen: un-hide when minimized to tray and
     * always re-raise + focus (the bump makes [BlazeWindow] re-run its raise even when the window
     * was already "visible", e.g. behind another app). The Settings dialog is owned by the frame, so
     * raising the frame raises it with - and a modal that opened while the app was hidden to the
     * tray would otherwise sit behind it, unreachable.
     */
    fun raiseWindow() {
        windowVisible.value = true
        windowRaiseToken.value += 1
    }

    /** Open the modal Settings window, surfacing the owner frame first if it is hidden. */
    fun openSettings() {
        raiseWindow()
        settingsOpen.value = true
    }

    /** Close the modal Settings window (leaving the in-flight draft uncommitted). */
    fun closeSettings() {
        settingsOpen.value = false
    }

    /** Minimize-to-tray can only work when the tray is both enabled and supported. */
    private val trayActive: Boolean
        get() = appSettings.trayEnabled && trayService.isSupported

    private val appSettings
        get() = appSettingsRepository.settings.value

    /** Hide the window into the tray (or quit, when the tray can't hold the app). */
    fun onCloseRequest() {
        if (trayActive && appSettings.minimizeToTrayOnClose && !exiting) {
            hideToTray()
        } else {
            quit()
        }
    }

    /** Fold a title-bar minimize into the single hide-to-tray model. */
    fun onMinimize() = hideToTray()

    /** Shut the download service down cleanly and exit the Compose application. */
    fun quit() {
        exiting = true
        runBlocking {
            downloadsApi.shutdown()
        }
        exitApplication()
    }

    /**
     * Hide the window into the tray (or quit, when the tray can't hold the app). The Settings window
     * goes with it: a modal dialog whose owner is unmapped would be left floating with nothing to be
     * modal to, and dropping it out of the composition runs the settings model's `onDispose` revert -
     * exactly the "closed without committing" the tray hide means.
     */
    fun hideToTray() {
        closeSettings()
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
    val windowRaiseToken = remember { mutableStateOf(0L) }
    val settingsOpen = remember { mutableStateOf(false) }
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    val trayService = koinInject<TrayService>()
    val downloadsApi = koinInject<DownloadsApi>()

    return remember(windowState, windowVisible, settingsOpen) {
        AppShell(
            windowState = windowState,
            windowVisible = windowVisible,
            windowRaiseToken = windowRaiseToken,
            settingsOpen = settingsOpen,
            appSettingsRepository = appSettingsRepository,
            trayService = trayService,
            downloadsApi = downloadsApi,
            exitApplication = ::exitApplication,
        )
    }
}
