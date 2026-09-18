package org.blaze.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import kotlin.math.ln
import kotlin.math.pow

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String) -> Unit
) {
    var url by remember { mutableStateOf(TextFieldValue("")) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(400.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Add New Download",
                    style = JewelTheme.defaultTextStyle
                )

                TextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://example.com/file.zip") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    DefaultButton(
                        onClick = {
                            if (url.text.isNotBlank()) {
                                onAdd(url.text)
                                onDismiss()
                            }
                        },
                        enabled = url.text.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(state: DownloadState) {
    val color = when (state) {
        DownloadState.QUEUED -> Color.Gray
        DownloadState.DOWNLOADING -> Color(0xFF4CAF50)
        DownloadState.PAUSED -> Color(0xFFFFA000)
        DownloadState.COMPLETED -> Color(0xFF2196F3)
        DownloadState.FAILED -> Color(0xFFF44336)
        DownloadState.REMOVING -> Color.DarkGray
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = state.name,
            style = JewelTheme.defaultTextStyle,
            color = color
        )
    }
}

@Composable
fun DownloadRow(
    download: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            key = if (download.state == DownloadState.COMPLETED) AllIconsKeys.FileTypes.Any_type else AllIconsKeys.Actions.Download,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.name,
                style = JewelTheme.defaultTextStyle
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalProgressBar(
                    progress = download.progress,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(download.progress * 100).toInt()}%",
                    style = JewelTheme.defaultTextStyle
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBadge(download.state)
                Text(
                    text = formatSize(download.downloadedSize) + " / " + formatSize(download.totalSize),
                    style = JewelTheme.defaultTextStyle
                )
                if (download.state == DownloadState.DOWNLOADING) {
                    Text(
                        text = "• " + formatSpeed(download.speed),
                        style = JewelTheme.defaultTextStyle
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (download.state) {
                DownloadState.DOWNLOADING -> {
                    IconButton(onClick = onPause) {
                        Icon(AllIconsKeys.Actions.Pause, null)
                    }
                }
                DownloadState.PAUSED, DownloadState.QUEUED -> {
                    IconButton(onClick = onResume) {
                        Icon(AllIconsKeys.Actions.Resume, null)
                    }
                }
                DownloadState.FAILED -> {
                    IconButton(onClick = onRetry) {
                        Icon(AllIconsKeys.Actions.Restart, null)
                    }
                }
                else -> {}
            }
            IconButton(onClick = onRemove) {
                Icon(AllIconsKeys.Actions.GC, null)
            }
        }
    }
}

@Composable
fun Sidebar(
    items: List<SidebarItem>,
    selectedItem: SidebarItem,
    onItemSelected: (SidebarItem) -> Unit
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(vertical = 12.dp)
    ) {
        items.forEach { item ->
            SidebarRow(
                item = item,
                isSelected = item == selectedItem,
                onClick = { onItemSelected(item) }
            )
        }
    }
}

@Composable
private fun SidebarRow(
    item: SidebarItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val style = JewelTheme.simpleListItemStyle
    val background = if (isSelected) style.colors.backgroundSelected else Color.Transparent
    val contentColor = if (isSelected) style.colors.contentSelected else Color.Unspecified

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            key = item.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = contentColor
        )
        Text(
            text = item.label,
            style = JewelTheme.defaultTextStyle,
            color = contentColor
        )
    }
}

data class SidebarItem(
    val label: String,
    val icon: IconKey,
    val id: String
)

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
}

private fun formatSpeed(bytesPerSec: Long): String {
    return formatSize(bytesPerSec) + "/s"
}