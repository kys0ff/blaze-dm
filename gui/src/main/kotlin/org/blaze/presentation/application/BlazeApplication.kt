package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.ApplicationScope
import org.blaze.i18n.LocalBlazeStrings
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

    CompositionLocalProvider(LocalBlazeStrings provides strings) {
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
