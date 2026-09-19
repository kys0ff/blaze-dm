package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.theme.IdeColors
import org.blaze.presentation.util.formatSize
import org.blaze.presentation.util.formatSpeed
import org.blaze.presentation.util.toFriendlyMessage
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

private val ListItemShape = RoundedCornerShape(6.dp)

@OptIn(ExperimentalJewelApi::class)
@Composable
fun DownloadRow(
    download: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val listColors = JewelTheme.simpleListItemStyle.colors

    val background = when {
        isSelected -> listColors.backgroundSelectedActive
        hovered -> IdeColors.hover
        else -> Color.Transparent
    }
    val primary = if (isSelected) listColors.contentSelectedActive else Color.Unspecified
    val secondary =
        if (isSelected) listColors.contentSelectedActive else JewelTheme.globalColors.text.info

    val showActions = hovered || isSelected || download.state == DownloadState.FAILED

    val isIndeterminate = download.state == DownloadState.DOWNLOADING &&
            download.totalSize == null && download.progress <= 0f

    val meta = buildString {
        append(formatSize(download.downloadedSize))
        if (download.totalSize != null) append(" of ").append(formatSize(download.totalSize))
        if (download.state == DownloadState.DOWNLOADING) append(", ").append(formatSpeed(download.speed))
        if (download.peers > 0) {
            append(", ").append(download.peers).append(if (download.peers == 1) " peer" else " peers")
        }
    }

    val smallText = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(ListItemShape)
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                key = if (download.state == DownloadState.COMPLETED) {
                    AllIconsKeys.FileTypes.Archive
                } else {
                    AllIconsKeys.Actions.Download
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = download.name,
                        color = primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(download.progress * 100).toInt()}%",
                        style = smallText,
                        color = secondary
                    )
                }

                Spacer(Modifier.height(4.dp))
                if (isIndeterminate) {
                    IndeterminateHorizontalProgressBar(modifier = Modifier.fillMaxWidth())
                } else {
                    HorizontalProgressBar(
                        progress = download.progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatusBadge(download.state)
                    Text(
                        text = meta,
                        style = smallText,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (download.state == DownloadState.FAILED) {
                        download.error?.let { error ->
                            Text(
                                text = error.toFriendlyMessage(),
                                style = smallText,
                                color = if (isSelected) primary else IdeColors.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.widthIn(min = 56.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (showActions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        when (download.state) {
                            DownloadState.DOWNLOADING ->
                                ToolbarIconButton(AllIconsKeys.Actions.Pause, "Pause", onPause)

                            DownloadState.PAUSED, DownloadState.QUEUED ->
                                ToolbarIconButton(AllIconsKeys.Actions.Resume, "Resume", onResume)

                            DownloadState.FAILED ->
                                ToolbarIconButton(AllIconsKeys.Actions.Restart, "Retry", onRetry)

                            else -> {}
                        }
                        if (download.state == DownloadState.DOWNLOADING ||
                            download.state == DownloadState.PAUSED ||
                            download.state == DownloadState.QUEUED
                        ) {
                            ToolbarIconButton(AllIconsKeys.Actions.Cancel, "Cancel", onCancel)
                        }
                        ToolbarIconButton(AllIconsKeys.Actions.GC, "Remove", onRemove)
                    }
                }
            }
        }
    }
}
