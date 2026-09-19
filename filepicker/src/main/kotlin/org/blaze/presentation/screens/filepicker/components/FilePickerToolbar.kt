package org.blaze.presentation.screens.filepicker.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.filepicker.FilePickerEvent
import org.blaze.presentation.screens.filepicker.FilePickerState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun FilePickerToolbar(
    state: FilePickerState,
    onEvent: (FilePickerEvent) -> Unit,
    strings: BlazeStrings
) {
    val fStrings = strings.filePicker
    val newFolderEnabled = state.selected != null

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarIconButton(
            key = AllIconsKeys.Nodes.HomeFolder,
            tooltip = fStrings.home,
            onClick = { onEvent(FilePickerEvent.GoHome) }
        )
        ToolbarIconButton(
            key = AllIconsKeys.Actions.NewFolder,
            tooltip = fStrings.newFolder,
            enabled = newFolderEnabled,
            onClick = { onEvent(FilePickerEvent.ShowNewFolder) }
        )
        ToolbarIconButton(
            key = AllIconsKeys.Actions.Refresh,
            tooltip = fStrings.refresh,
            onClick = { onEvent(FilePickerEvent.Refresh) }
        )
        Divider(
            Orientation.Vertical,
            modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
        )
        ToolbarIconButton(
            key = AllIconsKeys.Actions.ToggleVisibility,
            tooltip = if (state.showHidden) fStrings.hideHidden else fStrings.showHidden,
            onClick = { onEvent(FilePickerEvent.ToggleHiddenFiles) },
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (state.showHidden) {
                        JewelTheme.globalColors.text.normal.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    }
                )
        )
    }
}
