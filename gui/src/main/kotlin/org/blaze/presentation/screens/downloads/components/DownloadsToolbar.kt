package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.LocalShortcutLabels
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolWindowSearchField
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.SelectionActions
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ToggleableIconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsToolbar(
    hasActiveDownloads: Boolean,
    hasPausedDownloads: Boolean,
    hasCompletedDownloads: Boolean,
    hasSelection: Boolean,
    selectedCount: Int,
    /** Which bulk commands the current selection can actually obey; drives the buttons below. */
    selectionActions: SelectionActions,
    searchQuery: String,
    snailModeEnabled: Boolean,
    searchFocusRequester: FocusRequester,
    onEvent: (DownloadsEvent) -> Unit,
    onAddDownload: () -> Unit,
    onImportPortable: () -> Unit,
    /**
     * Asks the screen to clear the finished downloads. Deliberately not `onEvent(ClearCompleted)`:
     * a bulk, one-way list edit is confirmed first, and that decision belongs to the screen.
     */
    onClearCompleted: () -> Unit,
    /**
     * Selects every row of the current filter. Explicit toolbar button ([AllIconsKeys.Actions.Selectall])
     * and the header menu's first item, so bulk selection no longer hides behind a gesture.
     */
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBulkPause: () -> Unit,
    onBulkResume: () -> Unit,
    onBulkCancel: () -> Unit,
    onBulkRemove: () -> Unit,
    /** Whether the Details tool window is currently shown; drives the toggle button below. */
    detailsVisible: Boolean,
    /** Whether the current filter has any row at all — an empty table cannot be selected into. */
    hasVisibleRows: Boolean,
    onToggleDetails: (visible: Boolean) -> Unit,
) {
    val strings = blazeStrings
    val shortcuts = LocalShortcutLabels.current
    // While rows are selected the toolbar swaps its per-command buttons for a bulk-action cluster, so
    // the same space always shows the actions relevant to the current context.
    ToolWindowHeader(
        title = strings.downloads.title,
        actions = {
            if (hasSelection) {
                Text(
                    text = strings.downloads.selection.count(selectedCount),
                    style = org.jetbrains.jewel.foundation.theme.JewelTheme.defaultTextStyle,
                    modifier = Modifier.padding(end = 4.dp),
                )
                BulkActions(
                    strings = strings,
                    actions = selectionActions,
                    onClearSelection = onClearSelection,
                    onBulkPause = onBulkPause,
                    onBulkResume = onBulkResume,
                    onBulkCancel = onBulkCancel,
                    onBulkRemove = onBulkRemove,
                )
            } else {
                ToolWindowSearchField(
                    query = searchQuery,
                    placeholder = strings.downloads.searchPlaceholder,
                    clearDescription = strings.downloads.clearSearch,
                    onQueryChange = { onEvent(DownloadsEvent.SearchChanged(it)) },
                    focusRequester = searchFocusRequester,
                )
                Divider(
                    Orientation.Vertical,
                    modifier = Modifier.height(BlazeLayout.ToolbarSeparatorHeight).padding(horizontal = 4.dp)
                )
                ToolbarIconButton(
                    key = AllIconsKeys.General.Add,
                    tooltip = strings.downloads.toolbar.add,
                    onClick = onAddDownload,
                    shortcut = shortcuts.newDownload,
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Download,
                    tooltip = strings.downloads.toolbar.importPortable,
                    onClick = onImportPortable
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Resume,
                    tooltip = strings.downloads.toolbar.resumeAll,
                    enabled = hasPausedDownloads,
                    onClick = { onEvent(DownloadsEvent.ResumeAll) }
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Pause,
                    tooltip = strings.downloads.toolbar.pauseAll,
                    enabled = hasActiveDownloads,
                    onClick = { onEvent(DownloadsEvent.PauseAll) }
                )
                Divider(
                    Orientation.Vertical,
                    modifier = Modifier.height(BlazeLayout.ToolbarSeparatorHeight).padding(horizontal = 4.dp)
                )
                // The IDE spells "select all" with a button, not a hidden gesture: this takes the
                // whole current filter, and while a selection is alive the bulk group swaps it for its
                // mirror image, Deselect (see [BulkActions]).
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Selectall,
                    tooltip = strings.downloads.selection.selectAll,
                    shortcut = shortcuts.selectAll,
                    enabled = hasVisibleRows,
                    onClick = onSelectAll,
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.GC,
                    tooltip = strings.downloads.toolbar.clearCompleted,
                    enabled = hasCompletedDownloads,
                    onClick = onClearCompleted
                )
                ToggleableIconActionButton(
                    // A speed control, so it carries the speed glyph (bolt / stopwatch) rather than the
                    // red stop square it used: `Actions.Suspend` resolves to the New UI run-stop icon,
                    // which reads as "kill everything", not "throttle the rate".
                    key = if (snailModeEnabled) BlazeIcons.SnailMode else BlazeIcons.FullSpeed,
                    contentDescription = strings.downloads.toolbar.snailMode,
                    value = snailModeEnabled,
                    onValueChange = { onEvent(DownloadsEvent.ToggleSnailMode(it)) },
                    tooltip = { Text(strings.downloads.toolbar.snailMode) }
                )
                ToolbarIconButton(
                    key = AllIconsKeys.General.ShowInfos,
                    tooltip = if (detailsVisible) strings.downloads.detailsPane.hide else strings.downloads.detailsPane.show,
                    shortcut = shortcuts.toggleDetails,
                    onClick = { onToggleDetails(!detailsVisible) },
                )
            }
        }
    )
}

@Composable
private fun BulkActions(
    strings: org.blaze.i18n.BlazeStrings,
    actions: SelectionActions,
    onClearSelection: () -> Unit,
    onBulkPause: () -> Unit,
    onBulkResume: () -> Unit,
    onBulkCancel: () -> Unit,
    onBulkRemove: () -> Unit,
) {
    val s = strings.downloads.selection
    val shortcuts = LocalShortcutLabels.current
    // A command lights up only when some selected row would obey it, and its tooltip names that many
    // rows ("Pause 3 downloads"), so the button's enabled state, tooltip and effect always agree. A
    // finished download in the selection cannot light up "resume", and the count simply omits it.
    ToolbarIconButton(
        key = AllIconsKeys.Actions.Pause,
        tooltip = if (actions.canPause) s.pauseSelected(actions.pauseCount) else s.pause,
        enabled = actions.canPause,
        onClick = onBulkPause,
    )
    ToolbarIconButton(
        key = AllIconsKeys.Actions.Resume,
        tooltip = if (actions.canResume) s.resumeSelected(actions.resumeCount) else s.resume,
        enabled = actions.canResume,
        onClick = onBulkResume,
    )
    ToolbarIconButton(
        // Stopping running transfers is the IDE's "Stop", and it draws as the plain red square (see
        // [BlazeIcons.Stop]); the generic New UI cross this used to borrow said "close", not "abort".
        key = BlazeIcons.Stop,
        tooltip = if (actions.canCancel) s.cancelSelected(actions.cancelCount) else s.cancel,
        enabled = actions.canCancel,
        onClick = onBulkCancel,
    )
    ToolbarIconButton(
        key = AllIconsKeys.Actions.GC,
        tooltip = if (actions.canRemove) s.removeSelected(actions.removeCount) else s.remove,
        enabled = actions.canRemove,
        onClick = onBulkRemove,
    )
    // The mirror of the toolbar's Select All: same Ctrl/Cmd+A, same glyph family, and it drops the
    // whole selection instead of clearing a single row.
    ToolbarIconButton(
        key = AllIconsKeys.Actions.Unselectall,
        tooltip = s.deselect,
        shortcut = shortcuts.selectAll,
        onClick = onClearSelection,
    )
}
