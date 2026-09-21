package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ApplicationScope
import org.blaze.i18n.LocalBlazeStrings
import org.blaze.presentation.application.components.BlazeWindow

/**
 * Blaze's application root: a thin composition of the app-shell concerns, each
 * owned by a dedicated unit.
 *
 * - [rememberBlazeStrings]  - locale-driven UI strings,
 * - [rememberAppShell]      - window lifecycle (visibility, close-to-tray, quit),
 * - [AppTrayHost]           - system-tray wiring on top of the `:tray` library,
 * - [AppTheme]              - theme-mode + palette resolution on top of `BlazeTheme`.
 */
@Composable
fun ApplicationScope.BlazeApplication() {
    val strings = rememberBlazeStrings()
    val shell = rememberAppShell()

    AppTrayHost(strings = strings, shell = shell)

    CompositionLocalProvider(LocalBlazeStrings provides strings) {
        AppTheme {
            BlazeWindow(
                windowState = shell.windowState,
                visible = shell.windowVisible.value,
                onCloseRequest = shell::onCloseRequest,
                onMinimize = shell::onMinimize,
            )
        }
    }
}
