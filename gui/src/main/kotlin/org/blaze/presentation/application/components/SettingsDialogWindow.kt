package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.applyWindowsTitleBarTheme
import org.blaze.presentation.components.preventDialogMinimize
import org.blaze.presentation.screens.settings.LocalSettingsDialogDismiss
import org.blaze.presentation.screens.settings.SettingsScreen
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.awt.Dimension

/**
 * The modal Settings window, the way the IDE has it: a real OS window owned by the main frame rather
 * than a view swapped into the content area, with a search field above the category list, the section
 * to its right and OK / Cancel / Apply along the bottom.
 *
 * Compose's `DialogWindow` takes its owner from the window it is composed in (see [BlazeWindow]), so
 * this is document-modal: input to the main frame is blocked while it is open, it stays above that
 * frame, and it gets no taskbar entry. Decorations are the platform's own - the title bar is the OS's,
 * not Blaze's - which is what a modal is, and the title text moves there, which is why the content has
 * no header strip of its own.
 *
 * The native close button means Cancel, and it gets that for free: closing removes this window from
 * the composition, the [Navigator] below disposes its screen, and `SettingsScreenModel.onDispose`
 * reverts the uncommitted draft. [onDismiss] is what the OK / Cancel / Apply path calls through
 * [LocalSettingsDialogDismiss], and is also what the window's own close request reports.
 */
@Composable
fun SettingsDialogWindow(
    state: DialogState,
    onDismiss: () -> Unit,
) {
    val strings = blazeStrings

    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = strings.settings.title,
        resizable = true,
    ) {
        // The Settings modal is never minimizable: an iconified dialog would drag the owner frame
        // into the group minimize and hide the whole app to the tray.
        preventDialogMinimize()

        // Paint the native Windows caption with the panel background so the Settings dialog
        // reads as a single themed surface rather than a Jewel panel under an off-theme bar.
        applyWindowsTitleBarTheme(JewelTheme.globalColors.panelBackground)

        LaunchedEffect(window) {
            window.minimumSize = Dimension(720, 420)
        }

        CompositionLocalProvider(LocalSettingsDialogDismiss provides onDismiss) {
            Navigator(remember { SettingsScreen() })
        }
    }
}
