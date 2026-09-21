package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.blaze.data.AppSettingsRepository
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadRepository
import org.blaze.i18n.BlazeStrings
import org.blaze.platform.tray.AppTrayIcon
import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayMenuItem
import org.blaze.tray.api.TrayService
import org.blaze.tray.compose.TrayHost
import org.koin.compose.koinInject

/**
 * Wires Blaze's tray identity, artwork, and menu to the app-agnostic `:tray`
 * library's [TrayHost] wrapper (which owns install / Show-Hide sync / teardown).
 *
 * Window visibility and shutdown policy stay in [AppShell]; this composable only
 * translates them - plus live download state - into a [TrayConfig].
 */
@Composable
fun AppTrayHost(strings: BlazeStrings, shell: AppShell) {
    val trayService = koinInject<TrayService>()
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    val downloadRepository = koinInject<DownloadRepository>()
    val appScope = koinInject<CoroutineScope>()

    val appSettings by appSettingsRepository.settings.collectAsState()

    // Derive just enough download state to keep the tray menu honest: only offer
    // "Pause All" when something is still transferring/queued and "Resume All"
    // when something is paused. `distinctUntilChanged` collapses the frequent
    // progress ticks into the handful of transitions that actually matter, so the
    // tray is only rebuilt when an item must appear or disappear.
    val trayControls by downloadRepository.downloads
        .map { downloads ->
            TrayDownloadControls(
                canPauseAll = downloads.any {
                    it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
                },
                canResumeAll = downloads.any { it.state == DownloadState.PAUSED },
            )
        }
        .distinctUntilChanged()
        .collectAsState(initial = TrayDownloadControls())

    val trayConfig = remember(strings, trayControls) {
        val downloadItems = buildList {
            if (trayControls.canPauseAll) {
                add(TrayMenuItem.Item(strings.tray.pauseAll) {
                    appScope.launch { downloadRepository.pauseAll() }
                })
            }
            if (trayControls.canResumeAll) {
                add(TrayMenuItem.Item(strings.tray.resumeAll) {
                    appScope.launch { downloadRepository.resumeAll() }
                })
            }
        }
        TrayConfig(
            id = "org.blaze.Downloader",
            title = "Blaze",
            icon = { size -> AppTrayIcon.image(size) },
            menu = listOf(
                TrayMenuItem.WindowToggle(
                    hiddenLabel = strings.tray.show,
                    shownLabel = strings.tray.hide,
                ) { shell.windowVisible.value = !shell.windowVisible.value },
            ) + downloadItems + listOf(
                TrayMenuItem.Separator,
                TrayMenuItem.Item(strings.tray.quit) { shell.quit() },
            ),
        )
    }

    TrayHost(
        service = trayService,
        enabled = appSettings.trayEnabled,
        config = trayConfig,
        windowVisible = shell.windowVisible.value,
    )
}

/**
 * Minimal snapshot of download state used to decide which queue actions the tray
 * menu should expose. A data class so `distinctUntilChanged` only re-emits when a
 * flag actually flips, keeping tray rebuilds tied to real state changes.
 */
private data class TrayDownloadControls(
    val canPauseAll: Boolean = false,
    val canResumeAll: Boolean = false,
)
