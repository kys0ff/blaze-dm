package org.blaze.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.panel.ToolWindowMoveTo
import org.blaze.presentation.components.panel.ToolWindowResize
import org.blaze.presentation.components.panel.ToolWindowViewMode
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
    viewMode: ToolWindowViewMode,
    moveTo: ToolWindowMoveTo,
    onToggleGroupTabs: ((Boolean) -> Unit)? = null,
    groupTabs: Boolean? = null,
    onViewMode: (ToolWindowViewMode) -> Unit,
    onMoveTo: (ToolWindowMoveTo) -> Unit,
    onResize: (ToolWindowResize) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberMenuAnchor()

    // The one place the mode's own vocabulary decides what the menu can offer: a free window has no
    // split to stretch, so the Resize presets only mean something to the modes that hold a share of the
    // frame or cover an edge of it (see [ToolWindowViewMode.isFloatingWindow]).
    val isFloating = viewMode.isFloatingWindow
    val isRightDocked = !isFloating && (moveTo == ToolWindowMoveTo.RIGHT_TOP || moveTo == ToolWindowMoveTo.RIGHT_BOTTOM)
    val isLeftDocked = !isFloating && (moveTo == ToolWindowMoveTo.LEFT_TOP || moveTo == ToolWindowMoveTo.LEFT_BOTTOM)
    val isBottomDocked = !isFloating && (moveTo == ToolWindowMoveTo.BOTTOM_LEFT || moveTo == ToolWindowMoveTo.BOTTOM_RIGHT)

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
                                marked(options.dockPinned, selected = viewMode == ToolWindowViewMode.DOCK_PINNED) {
                                    onViewMode(ToolWindowViewMode.DOCK_PINNED)
                                },
                                marked(options.dockUnpinned, selected = viewMode == ToolWindowViewMode.DOCK_UNPINNED) {
                                    onViewMode(ToolWindowViewMode.DOCK_UNPINNED)
                                },
                                marked(options.undock, selected = viewMode == ToolWindowViewMode.UNDOCK) {
                                    onViewMode(ToolWindowViewMode.UNDOCK)
                                },
                                marked(options.float, selected = viewMode == ToolWindowViewMode.FLOAT) {
                                    onViewMode(ToolWindowViewMode.FLOAT)
                                },
                                marked(options.window, selected = viewMode == ToolWindowViewMode.WINDOW) {
                                    onViewMode(ToolWindowViewMode.WINDOW)
                                },
                            ),
                        ),
                    )
                    add(
                        MenuEntry.Submenu(
                            label = options.moveTo,
                            children = listOf(
                                marked(options.leftTop, selected = moveTo == ToolWindowMoveTo.LEFT_TOP) {
                                    onMoveTo(ToolWindowMoveTo.LEFT_TOP)
                                },
                                marked(options.leftBottom, selected = moveTo == ToolWindowMoveTo.LEFT_BOTTOM) {
                                    onMoveTo(ToolWindowMoveTo.LEFT_BOTTOM)
                                },
                                marked(options.bottomRight, selected = moveTo == ToolWindowMoveTo.BOTTOM_RIGHT) {
                                    onMoveTo(ToolWindowMoveTo.BOTTOM_RIGHT)
                                },
                                marked(options.bottomLeft, selected = moveTo == ToolWindowMoveTo.BOTTOM_LEFT) {
                                    onMoveTo(ToolWindowMoveTo.BOTTOM_LEFT)
                                },
                                marked(options.rightBottom, selected = moveTo == ToolWindowMoveTo.RIGHT_BOTTOM) {
                                    onMoveTo(ToolWindowMoveTo.RIGHT_BOTTOM)
                                },
                                marked(options.rightTop, selected = moveTo == ToolWindowMoveTo.RIGHT_TOP) {
                                    onMoveTo(ToolWindowMoveTo.RIGHT_TOP)
                                },
                            ),
                        ),
                    )
                    add(
                        MenuEntry.Submenu(
                            label = options.resize,
                            children = listOf(
                                marked(options.stretchLeft, enabled = canStretchLeft) { onResize(ToolWindowResize.STRETCH_LEFT) },
                                marked(options.stretchRight, enabled = canStretchRight) { onResize(ToolWindowResize.STRETCH_RIGHT) },
                                marked(options.stretchTop, enabled = canStretchTop) { onResize(ToolWindowResize.STRETCH_TOP) },
                                marked(options.stretchBottom, enabled = canStretchBottom) { onResize(ToolWindowResize.STRETCH_BOTTOM) },
                                marked(options.maximize) { onResize(ToolWindowResize.MAXIMIZE) },
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
