package org.blaze.presentation.screens.filepicker.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.filepicker.model.FileTreeRow
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun FilePickerTreeRow(
    row: FileTreeRow,
    isExpanded: Boolean,
    isSelected: Boolean,
    treeFocused: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDoubleClick: () -> Unit,
    strings: BlazeStrings
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = JewelTheme.simpleListItemStyle.colors

    val activeSelection = isSelected && treeFocused
    val background = when {
        activeSelection -> colors.backgroundSelectedActive
        isSelected -> colors.backgroundSelectedActive.copy(alpha = 0.45f)
        hovered -> JewelTheme.globalColors.text.normal.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    val contentColor = if (activeSelection) colors.contentSelectedActive else Color.Unspecified

    val select by rememberUpdatedState(onSelect)
    val toggle by rememberUpdatedState(onToggle)
    val doubleClick by rememberUpdatedState(onDoubleClick)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .hoverable(interaction)
                .pointerInput(row.node.path) {
                    detectTapGestures(
                        onPress = { select() },
                        onDoubleTap = { doubleClick() }
                    )
                }
                .padding(start = 4.dp + (row.depth * 16).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .pointerInput(row.node.path) {
                        detectTapGestures(onPress = { toggle() })
                    },
                contentAlignment = Alignment.Center
            ) {
                if (row.node.isDirectory) {
                    Icon(
                        key = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                        contentDescription = if (isExpanded) strings.filePicker.collapse else strings.filePicker.expand,
                        tint = contentColor
                    )
                }
            }
            Icon(
                key = if (row.node.isDirectory) AllIconsKeys.Nodes.Folder else AllIconsKeys.FileTypes.Any_type,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = row.node.displayName,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
