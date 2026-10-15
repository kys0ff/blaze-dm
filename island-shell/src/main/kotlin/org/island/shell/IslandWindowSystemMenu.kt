package org.island.shell

import androidx.compose.runtime.Composable
import org.island.foundation.window.DialogChromeGuard
import org.island.ui.menu.IslandMenu
import org.island.ui.menu.MenuAnchor
import org.island.ui.menu.MenuEntry
import org.island.ui.text.LocalIslandUiStrings

/**
 * The square window menu the title-bar app icon opens - the shell's take on the classic system
 * menu. Shares [IslandMenu]'s renderer (and its keyboard walk and geometry-based dismissal) with
 * every other popup in the app, but drawn with right-angle corners and at its own natural width,
 * because a system menu is not part of the app's menu-bar width group.
 *
 * Same rule the IDE's own window menu follows - an impossible transition is *disabled*, never
 * hidden - so the shape of the menu never changes, only the state of its rows:
 *
 *  - Restore answers only a maximized window; Maximize only a floating one - the pair can never
 *    both be live, exactly like the title-bar buttons they mirror.
 *  - Minimize folds into the app's hide policy, and a modal dialog makes that fold wrong (see the
 *    frame's minimize listener in [IslandWindow]), so it is disabled while any dialog is open.
 *  - Close is the frame's own close request, with its access key underlined the way the classic
 *    system menus underline it.
 */
@Composable
fun IslandWindowSystemMenu(
    host: IslandWindowHost,
    anchor: MenuAnchor,
    onDismissRequest: () -> Unit,
) {
    val strings = LocalIslandUiStrings.current
    // Read live from the composition-local window state: a maximize or restore done through the
    // title-bar buttons or the window manager recomposes these enabled flags while the menu sits
    // open, so its rows never lie about the frame.
    val isMaximized = host.isMaximized()
    IslandMenu(
        entries = islandWindowMenuEntries(
            restoreLabel = strings.windowRestore(),
            minimizeLabel = strings.windowMinimize(),
            maximizeLabel = strings.windowMaximize(),
            closeLabel = strings.windowClose(),
            isMaximized = isMaximized,
            minimizeEnabled = !DialogChromeGuard.hasOpenDialog(),
            onRestore = host::restoreWindow,
            onMinimize = host::onMinimize,
            onMaximize = host::maximizeWindow,
            onClose = host::onCloseRequest,
        ),
        anchor = anchor,
        onDismissRequest = onDismissRequest,
        unifyWidth = false,
        squareCorners = true,
    )
}

/**
 * The rows of the window menu, built from the two facts the menu reflects: whether the frame is
 * maximized and whether a minimize is currently allowed. Exposed as a plain function so a host
 * can test the enable/disable rules without a composition.
 */
fun islandWindowMenuEntries(
    restoreLabel: String,
    minimizeLabel: String,
    maximizeLabel: String,
    closeLabel: String,
    isMaximized: Boolean,
    minimizeEnabled: Boolean,
    onRestore: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
): List<MenuEntry> = listOf(
    MenuEntry.Action(label = restoreLabel, enabled = isMaximized, onClick = onRestore),
    MenuEntry.Action(label = minimizeLabel, enabled = minimizeEnabled, onClick = onMinimize),
    MenuEntry.Action(label = maximizeLabel, enabled = !isMaximized, onClick = onMaximize),
    MenuEntry.Separator,
    MenuEntry.Action(
        label = closeLabel,
        // The first letter is the row's access key, underlined like the OS system menu draws it.
        mnemonic = 0,
        onClick = onClose,
    ),
)
