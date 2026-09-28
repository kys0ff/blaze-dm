package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DownloadsEvent
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
    searchQuery: String,
    snailModeEnabled: Boolean,
    searchFocusRequester: FocusRequester,
    onEvent: (DownloadsEvent) -> Unit,
    onAddDownload: () -> Unit,
    onImportPortable: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBulkPause: () -> Unit,
    onBulkResume: () -> Unit,
    onBulkCancel: () -> Unit,
    onBulkRemove: () -> Unit,
) {
    val strings = blazeStrings
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
                BulkActions(strings, onClearSelection, onBulkPause, onBulkResume, onBulkCancel, onBulkRemove)
            } else {
                DownloadsSearchField(
                    query = searchQuery,
                    onQueryChange = { onEvent(DownloadsEvent.SearchChanged(it)) },
                    focusRequester = searchFocusRequester,
                )
                Divider(
                    Orientation.Vertical,
                    modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
                )
                ToolbarIconButton(
                    key = AllIconsKeys.General.Add,
                    tooltip = strings.downloads.toolbar.add,
                    onClick = onAddDownload
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
                    modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.GC,
                    tooltip = strings.downloads.toolbar.clearCompleted,
                    enabled = hasCompletedDownloads,
                    onClick = { onEvent(DownloadsEvent.ClearCompleted) }
                )
                ToggleableIconActionButton(
                    key = AllIconsKeys.Actions.Suspend,
                    contentDescription = strings.downloads.toolbar.snailMode,
                    value = snailModeEnabled,
                    onValueChange = { onEvent(DownloadsEvent.ToggleSnailMode(it)) },
                    tooltip = { Text(strings.downloads.toolbar.snailMode) }
                )
            }
        }
    )
}

@Composable
private fun BulkActions(
    strings: org.blaze.i18n.BlazeStrings,
    onClearSelection: () -> Unit,
    onBulkPause: () -> Unit,
    onBulkResume: () -> Unit,
    onBulkCancel: () -> Unit,
    onBulkRemove: () -> Unit,
) {
    val s = strings.downloads.selection
    ToolbarIconButton(key = AllIconsKeys.Actions.Pause, tooltip = s.pause, onClick = onBulkPause)
    ToolbarIconButton(key = AllIconsKeys.Actions.Resume, tooltip = s.resume, onClick = onBulkResume)
    ToolbarIconButton(key = AllIconsKeys.Actions.Cancel, tooltip = s.cancel, onClick = onBulkCancel)
    ToolbarIconButton(key = AllIconsKeys.Actions.GC, tooltip = s.remove, onClick = onBulkRemove)
    ToolbarIconButton(key = AllIconsKeys.General.Close, tooltip = s.clearSelection, onClick = onClearSelection)
}
