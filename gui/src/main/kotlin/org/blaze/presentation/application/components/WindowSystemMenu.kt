package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppShell
import org.blaze.presentation.components.BlazeMenu
import org.blaze.presentation.components.DialogChromeGuard
import org.blaze.presentation.components.MenuAnchor
import org.blaze.presentation.components.MenuEntry

/**
 * The rows of the window menu, built from the two facts the menu reflects: whether the frame is
 * maximized and whether a minimize is currently allowed. Same rule the IDE's own window menu
 * follows — an impossible transition is *disabled*, never hidden — so the shape of the menu never
 * changes, only the state of its rows:
 *
 *  - Restore answers only a maximized window; Maximize only a floating one — the pair can never
 *    both be live, exactly like the title-bar buttons they mirror.
 *  - Minimize folds into the tray, and a modal dialog makes that fold wrong (see the frame's
 *    minimize listener in [BlazeWindow]), so it is disabled while any dialog is open.
 *  - Close is the frame's own close request, with its access key underlined the way the classic
 *    system menus underline it.
 */
internal fun windowMenuEntries(
    strings: BlazeStrings,
    isMaximized: Boolean,
    minimizeEnabled: Boolean,
    onRestore: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
): List<MenuEntry> = listOf(
    MenuEntry.Action(
        label = strings.menu.windowRestore,
        enabled = isMaximized,
        onClick = onRestore,
    ),
    MenuEntry.Action(
        label = strings.menu.windowMinimize,
        enabled = minimizeEnabled,
        onClick = onMinimize,
    ),
    MenuEntry.Action(
        label = strings.menu.windowMaximize,
        enabled = !isMaximized,
        onClick = onMaximize,
    ),
    MenuEntry.Separator,
    MenuEntry.Action(
        label = strings.menu.windowClose,
        // The first letter is the row's access key, underlined like the OS system menu draws it.
        mnemonic = 0,
        onClick = onClose,
    ),
)

/**
 * The square window menu the title-bar app icon opens — Blaze's take on the classic system menu,
 * sharing [BlazeMenu]'s renderer (and its keyboard walk and geometry-based dismissal) with every
 * other popup in the app, but drawn with right-angle corners and at its own natural width, because
 * a system menu is not part of the app's menu-bar width group.
 */
@Composable
fun WindowSystemMenu(
    shell: AppShell,
    anchor: MenuAnchor,
    onDismissRequest: () -> Unit,
) {
    val strings = blazeStrings
    // Read live from the composition-local window state: a maximize or restore done through the
    // title-bar buttons or the window manager recomposes these enabled flags while the menu sits
    // open, so its rows never lie about the frame.
    val isMaximized = shell.isMaximized()
    BlazeMenu(
        entries = windowMenuEntries(
            strings = strings,
            isMaximized = isMaximized,
            minimizeEnabled = !DialogChromeGuard.hasOpenDialog(),
            onRestore = shell::restoreWindow,
            onMinimize = shell::onMinimize,
            onMaximize = shell::maximizeWindow,
            onClose = shell::onCloseRequest,
        ),
        anchor = anchor,
        onDismissRequest = onDismissRequest,
        unifyWidth = false,
        squareCorners = true,
    )
}
