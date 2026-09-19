package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DownloadsEvent
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
    ToolWindowHeader(
        title = "Downloads",
        actions = {
            ToolbarIconButton(
                key = AllIconsKeys.General.Add,
                tooltip = "Add download",
                onClick = onAddDownload
            )
            ToolbarIconButton(
                key = AllIconsKeys.Actions.Resume,
                tooltip = "Resume all",
                enabled = hasPausedDownloads,
                onClick = { onEvent(DownloadsEvent.ResumeAll) }
            )
            ToolbarIconButton(
                key = AllIconsKeys.Actions.Pause,
                tooltip = "Pause all",
                enabled = hasActiveDownloads,
                onClick = { onEvent(DownloadsEvent.PauseAll) }
            )
            Divider(
                Orientation.Vertical,
                modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
            )
            ToolbarIconButton(
                key = AllIconsKeys.Actions.GC,
                tooltip = "Clear completed",
                enabled = hasCompletedDownloads,
                onClick = { onEvent(DownloadsEvent.ClearCompleted) }
            )
        }
    )
}
