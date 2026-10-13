package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.ApplicationScope
import org.blaze.data.AppSettingsRepository
import org.blaze.domain.repository.DownloadRepository
import org.blaze.i18n.BlazeLocale
import org.blaze.i18n.LocalBlazeStrings
import org.blaze.i18n.LocaleManager
import org.blaze.i18n.isRtl
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.presentation.application.components.BlazeWindow
import org.blaze.security.ConsentService
import org.koin.compose.koinInject
import org.island.theme.ui.*
import org.island.ui.menu.*

/**
 * Blaze's application root: a thin composition of the app-shell concerns, each
 * owned by a dedicated unit.
 *
 * - [rememberBlazeStrings]  - locale-driven UI strings,
 * - [rememberAppShell]      - window lifecycle (visibility, close-to-tray, the modal Settings window, quit),
 * - [AppTrayHost]           - system-tray wiring on top of the `:tray` library,
 * - [AppTaskbarProgress]    - aggregate download progress on the taskbar / dock icon,
 * - [AppClipboardMonitor]   - optional clipboard watching that feeds the external-open inbox,
 * - [AppSystemNotifications] - desktop notifications for download lifecycle changes, gated
 *      to fire only while the window is out of sight,
 * - [AppTheme]              - theme-mode + palette resolution on top of `IslandTheme`.
 */
@Composable
fun ApplicationScope.BlazeApplication() {
    val strings = rememberBlazeStrings()
    val shell = rememberAppShell()

    // The title-bar hamburger menu raises app-wide commands on a shared bus. The global ones the shell
    // can serve without any screen being up (exit, and the same pause/resume-all the tray already does
    // through the repository) are handled here; the view-scoped ones are picked up by the model and the
    // screens. This keeps every menu item working no matter which screen is currently visible.
    val commandBus = koinInject<AppCommandBus>()
    val downloadRepository = koinInject<DownloadRepository>()
    val serverController = koinInject<org.blaze.server.DownloadServiceController>()
    LaunchedEffect(commandBus, shell) {
        commandBus.commands.collect { command ->
            when (command) {
                AppCommand.Exit -> shell.quit()
                // Settings is a window, not a route, so opening it belongs to the shell: it works from
                // the title bar, the stripe or an external request with no screen having to host it.
                AppCommand.OpenSettings -> shell.openSettings()
                AppCommand.PauseAll -> downloadRepository.pauseAll()
                AppCommand.ResumeAll -> downloadRepository.resumeAll()
                // The local API server's lifecycle is a process-wide concern the shell owns, exactly
                // like exit; the log window itself is a view, so ShowServerLog falls to MainScreen.
                AppCommand.StartServer -> serverController.start()
                AppCommand.StopServer -> serverController.stop()
                AppCommand.RestartServer -> serverController.restart()
                else -> Unit
            }
        }
    }

    // An external open (or a macOS Dock reopen) must always surface the window - including while it
    // is hidden to the tray. The requests themselves stay queued in the inbox for the downloads
    // screen, so raising and importing can never race each other.
    val inbox = koinInject<ExternalOpenInbox>()
    val pendingOpens by inbox.pending.collectAsState()
    val bringToFront by inbox.bringToFrontSignal.collectAsState()
    LaunchedEffect(pendingOpens.size, bringToFront) {
        if (pendingOpens.isNotEmpty() || bringToFront > 0L) shell.raiseWindow()
    }

    // A consent prompt is the one request that must reach the user, not sit queued: an external
    // client's call and an extension jar's load are both suspended behind it, and Blaze may well be
    // hidden to the tray when it arrives - so surfacing the window is part of asking.
    val consentService = koinInject<ConsentService>()
    val pendingConsent by consentService.pending.collectAsState()
    LaunchedEffect(pendingConsent.size) {
        if (pendingConsent.isNotEmpty()) shell.raiseWindow()
    }

    AppTrayHost(strings = strings, shell = shell)
    AppTaskbarProgress()
    AppClipboardMonitor()
    AppSystemNotifications(shell = shell, strings = strings)

    // Language is persisted in the app settings (the same buffered draft the settings screen
    // edits), so Apply/OK - and the revert when leaving without committing - all flow through
    // this single sync point into the LocaleManager that rememberBlazeStrings reads.
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    val localeManager = koinInject<LocaleManager>()
    val appSettings by appSettingsRepository.settings.collectAsState()
    LaunchedEffect(appSettings.languageCode) {
        localeManager.setLocale(
            BlazeLocale.entries.firstOrNull { it.code == appSettings.languageCode }
                ?: BlazeLocale.English
        )
    }
    val currentLocale by localeManager.currentLocale.collectAsState()

    // One menu-width group for the whole app: every IslandMenu popup — title-bar menus, row context
    // menus, header and details menus — draws at the shared width the IDE gives all its popups,
    // across windows and dialogs, not just within one menu bar.
    val menuWidthGroup = rememberMenuWidthGroup()

    // RTL locales (Arabic) mirror the whole layout through Compose's layout-direction local;
    // text alignment, paddings and icon order all follow it.
    CompositionLocalProvider(
        LocalBlazeStrings provides strings,
        LocalMenuWidthGroup provides menuWidthGroup,
        LocalLayoutDirection provides if (currentLocale.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        AppTheme {
            BlazeWindow(shell = shell)
        }
    }
}
