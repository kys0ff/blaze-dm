package org.blaze.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsResize
import org.blaze.presentation.screens.downloads.DetailsViewMode
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The shared tool-window "more" button — the New UI draws a vertical ellipsis here, not a gear,
 * because the menu configures *this window* (view mode, dock position, size) rather than app settings.
 * Both the Details pane and the Server Logs window mount the exact same IDE-style option menu, so the
 * markup lives here once and each caller only supplies its current layout values and the callbacks that
 * carry a change back to whoever owns persistence — the button itself stays purely presentational.
 *
 * @param groupTabs the pane's "group tabs into one view" toggle state, or null for a window that has no
 *   tabs to group (the Server Logs window); a null hides that entry and its separator entirely.
 * @param onToggleGroupTabs fires with the new value when the group-tabs row is clicked; required when
 *   [groupTabs] is non-null, ignored otherwise.
 */
@Composable
fun ToolWindowOptionsButton(
    options: BlazeStrings.Downloads.DetailsPane.ToolWindowOptions,
    viewMode: DetailsViewMode,
    moveTo: DetailsMoveTo,
    onToggleGroupTabs: ((Boolean) -> Unit)? = null,
    groupTabs: Boolean? = null,
    onViewMode: (DetailsViewMode) -> Unit,
    onMoveTo: (DetailsMoveTo) -> Unit,
    onResize: (DetailsResize) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberMenuAnchor()

    // The one place the mode's own vocabulary decides what the menu can offer: a free window has no
    // split to stretch, so the Resize presets only mean something to the modes that hold a share of the
    // frame or cover an edge of it (see [DetailsViewMode.isFloatingWindow]).
    val isFloating = viewMode.isFloatingWindow
    val isRightDocked = !isFloating && (moveTo == DetailsMoveTo.RIGHT_TOP || moveTo == DetailsMoveTo.RIGHT_BOTTOM)
    val isLeftDocked = !isFloating && (moveTo == DetailsMoveTo.LEFT_TOP || moveTo == DetailsMoveTo.LEFT_BOTTOM)
    val isBottomDocked = !isFloating && (moveTo == DetailsMoveTo.BOTTOM_LEFT || moveTo == DetailsMoveTo.BOTTOM_RIGHT)

    val canStretchLeft = !isFloating && !isLeftDocked
    val canStretchRight = !isFloating && !isRightDocked
    val canStretchBottom = !isFloating && !isBottomDocked
    val canStretchTop = !isFloating

    Box(modifier = anchor.trackingModifier) {
        ToolbarIconButton(
            key = AllIconsKeys.Actions.More,
            tooltip = options.menuTooltip,
            onClick = { expanded = true },
        )
        if (expanded) {
            BlazeMenu(
                entries = buildList {
                    if (groupTabs != null && onToggleGroupTabs != null) {
                        add(
                            MenuEntry.Action(
                                label = options.groupTabs,
                                checked = groupTabs,
                                onClick = { onToggleGroupTabs(!groupTabs) },
                            ),
                        )
                        add(MenuEntry.Separator)
                    }
                    add(
                        MenuEntry.Submenu(
                            label = options.viewMode,
                            children = listOf(
                                marked(options.dockPinned, selected = viewMode == DetailsViewMode.DOCK_PINNED) {
                                    onViewMode(DetailsViewMode.DOCK_PINNED)
                                },
                                marked(options.dockUnpinned, selected = viewMode == DetailsViewMode.DOCK_UNPINNED) {
                                    onViewMode(DetailsViewMode.DOCK_UNPINNED)
                                },
                                marked(options.undock, selected = viewMode == DetailsViewMode.UNDOCK) {
                                    onViewMode(DetailsViewMode.UNDOCK)
                                },
                                marked(options.float, selected = viewMode == DetailsViewMode.FLOAT) {
                                    onViewMode(DetailsViewMode.FLOAT)
                                },
                                marked(options.window, selected = viewMode == DetailsViewMode.WINDOW) {
                                    onViewMode(DetailsViewMode.WINDOW)
                                },
                            ),
                        ),
                    )
                    add(
                        MenuEntry.Submenu(
                            label = options.moveTo,
                            children = listOf(
                                marked(options.leftTop, selected = moveTo == DetailsMoveTo.LEFT_TOP) {
                                    onMoveTo(DetailsMoveTo.LEFT_TOP)
                                },
                                marked(options.leftBottom, selected = moveTo == DetailsMoveTo.LEFT_BOTTOM) {
                                    onMoveTo(DetailsMoveTo.LEFT_BOTTOM)
                                },
                                marked(options.bottomRight, selected = moveTo == DetailsMoveTo.BOTTOM_RIGHT) {
                                    onMoveTo(DetailsMoveTo.BOTTOM_RIGHT)
                                },
                                marked(options.bottomLeft, selected = moveTo == DetailsMoveTo.BOTTOM_LEFT) {
                                    onMoveTo(DetailsMoveTo.BOTTOM_LEFT)
                                },
                                marked(options.rightBottom, selected = moveTo == DetailsMoveTo.RIGHT_BOTTOM) {
                                    onMoveTo(DetailsMoveTo.RIGHT_BOTTOM)
                                },
                                marked(options.rightTop, selected = moveTo == DetailsMoveTo.RIGHT_TOP) {
                                    onMoveTo(DetailsMoveTo.RIGHT_TOP)
                                },
                            ),
                        ),
                    )
                    add(
                        MenuEntry.Submenu(
                            label = options.resize,
                            children = listOf(
                                marked(options.stretchLeft, enabled = canStretchLeft) { onResize(DetailsResize.STRETCH_LEFT) },
                                marked(options.stretchRight, enabled = canStretchRight) { onResize(DetailsResize.STRETCH_RIGHT) },
                                marked(options.stretchTop, enabled = canStretchTop) { onResize(DetailsResize.STRETCH_TOP) },
                                marked(options.stretchBottom, enabled = canStretchBottom) { onResize(DetailsResize.STRETCH_BOTTOM) },
                                marked(options.maximize) { onResize(DetailsResize.MAXIMIZE) },
                            ),
                        ),
                    )
                },
                anchor = anchor,
                onDismissRequest = { expanded = false },
                alignRootToEnd = true,
            )
        }
    }
}

/**
 * One row of a tool-window option menu. The current choice of a group is *marked* in the icon column and
 * stays clickable — an IDE greys nothing out just to say "you are here". Only a row that the current view
 * mode cannot act on at all (stretching the split of a window that has no split) is disabled.
 */
private fun marked(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
): MenuEntry.Action =
    MenuEntry.Action(label = label, checked = selected.takeIf { selected }, enabled = enabled, onClick = onClick)
