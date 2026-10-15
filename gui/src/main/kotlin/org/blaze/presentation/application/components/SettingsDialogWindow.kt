package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogState
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.settings.LocalSettingsDialogDismiss
import org.blaze.presentation.screens.settings.SettingsScreen
import org.island.ui.dialog.IslandDialogWindow

/**
 * The modal Settings window, the way the IDE has it: a real OS window owned by the main frame rather
 * than a view swapped into the content area, with a search field above the category list, the section
 * to its right and OK / Cancel / Apply along the bottom.
 *
 * It is composed through the shell's [IslandDialogWindow], so the window chrome - document-modal
 * ownership, the never-minimizable guard and the DWM-pinned native caption - comes from the single
 * source of truth every dialog in the app shares; this file supplies only the Settings body and the
 * two Settings-specific facts (resizable, 720x420 minimum).
 *
 * The hoisted [state] (owned by [BlazeWindow]) keeps the user's resize and drag across closing and
 * reopening Settings within a session. The native close button means Cancel: closing removes this
 * window from the composition, the [Navigator] disposes its screen, and `SettingsScreenModel.onDispose`
 * reverts the uncommitted draft. [onDismiss] is what the OK / Cancel / Apply path calls through
 * [LocalSettingsDialogDismiss], and is also what the window's own close request reports.
 */
@Composable
fun SettingsDialogWindow(
    state: DialogState,
    onDismiss: () -> Unit,
) {
    val strings = blazeStrings

    IslandDialogWindow(
        onDismiss = onDismiss,
        state = state,
        title = strings.settings.title,
        resizable = true,
        minimumSizePx = 720 to 420,
    ) {
        CompositionLocalProvider(LocalSettingsDialogDismiss provides onDismiss) {
            Navigator(remember { SettingsScreen() })
        }
    }
}
