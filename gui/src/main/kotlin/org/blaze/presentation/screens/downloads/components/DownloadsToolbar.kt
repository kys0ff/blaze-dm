package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.i18n.blazeStrings
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun DownloadsToolbar(
    hasActiveDownloads: Boolean,
    hasPausedDownloads: Boolean,
    hasCompletedDownloads: Boolean,
    onEvent: (DownloadsEvent) -> Unit,
    onAddDownload: () -> Unit,
) {
    val strings = blazeStrings
    ToolWindowHeader(
        title = strings.downloads.title,
        actions = {
            ToolbarIconButton(
                key = AllIconsKeys.General.Add,
                tooltip = strings.downloads.toolbar.add,
                onClick = onAddDownload
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
        }
    )
}
