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
import org.blaze.i18n.BlazeLocale
import org.blaze.i18n.LocalBlazeStrings
import org.blaze.i18n.LocaleManager
import org.blaze.i18n.isRtl
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.presentation.application.components.BlazeWindow
import org.koin.compose.koinInject

/**
 * Blaze's application root: a thin composition of the app-shell concerns, each
 * owned by a dedicated unit.
 *
 * - [rememberBlazeStrings]  - locale-driven UI strings,
 * - [rememberAppShell]      - window lifecycle (visibility, close-to-tray, quit),
 * - [AppTrayHost]           - system-tray wiring on top of the `:tray` library,
 * - [AppTaskbarProgress]    - aggregate download progress on the taskbar / dock icon,
 * - [AppClipboardMonitor]   - optional clipboard watching that feeds the external-open inbox,
 * - [AppTheme]              - theme-mode + palette resolution on top of `BlazeTheme`.
 */
@Composable
fun ApplicationScope.BlazeApplication() {
    val strings = rememberBlazeStrings()
    val shell = rememberAppShell()

    // An external open (or a macOS Dock reopen) must always surface the window - including while it
    // is hidden to the tray. The requests themselves stay queued in the inbox for the downloads
    // screen, so raising and importing can never race each other.
    val inbox = koinInject<ExternalOpenInbox>()
    val pendingOpens by inbox.pending.collectAsState()
    val bringToFront by inbox.bringToFrontSignal.collectAsState()
    LaunchedEffect(pendingOpens.size, bringToFront) {
        if (pendingOpens.isNotEmpty() || bringToFront > 0L) shell.raiseWindow()
    }

    AppTrayHost(strings = strings, shell = shell)
    AppTaskbarProgress()
    AppClipboardMonitor()

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

    // RTL locales (Arabic) mirror the whole layout through Compose's layout-direction local;
    // text alignment, paddings and icon order all follow it.
    CompositionLocalProvider(
        LocalBlazeStrings provides strings,
        LocalLayoutDirection provides if (currentLocale.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        AppTheme {
            BlazeWindow(
                windowState = shell.windowState,
                visible = shell.windowVisible.value,
                raiseToken = shell.windowRaiseToken.value,
                onCloseRequest = shell::onCloseRequest,
                onMinimize = shell::onMinimize,
            )
        }
    }
}
