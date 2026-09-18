package org.blaze.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
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
                .width(450.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Add New Download",
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )

                TextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("Enter download URL (HTTP, Magnet, Torrent)") },
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
                    Spacer(Modifier.width(12.dp))
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
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = state.name.lowercase().replaceFirstChar { it.uppercase() },
            style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp),
            color = color
        )
    }
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
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
            .height(64.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            key = if (download.state == DownloadState.COMPLETED) AllIconsKeys.FileTypes.Archive else AllIconsKeys.Actions.Download,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (download.state == DownloadState.COMPLETED) Color.Unspecified else JewelTheme.globalColors.text.info
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = download.name,
                    style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Text(
                    text = "${(download.progress * 100).toInt()}%",
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = JewelTheme.globalColors.text.disabled)
                )
            }
            
            Spacer(Modifier.height(4.dp))
            HorizontalProgressBar(
                progress = download.progress,
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
            Spacer(Modifier.height(4.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBadge(download.state)
                val totalSizeText = if (download.totalSize != null) " / " + formatSize(download.totalSize) else ""
                Text(
                    text = formatSize(download.downloadedSize) + totalSizeText,
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = JewelTheme.globalColors.text.disabled)
                )
                if (download.state == DownloadState.DOWNLOADING) {
                    Text(
                        text = "• " + formatSpeed(download.speed),
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when (download.state) {
                DownloadState.DOWNLOADING -> {
                    ActionButton(onClick = onPause, tooltip = { Text("Pause") }) {
                        Icon(AllIconsKeys.Actions.Pause, null)
                    }
                }
                DownloadState.PAUSED, DownloadState.QUEUED -> {
                    ActionButton(onClick = onResume, tooltip = { Text("Resume") }) {
                        Icon(AllIconsKeys.Actions.Resume, null)
                    }
                }
                DownloadState.FAILED -> {
                    ActionButton(onClick = onRetry, tooltip = { Text("Retry") }) {
                        Icon(AllIconsKeys.Actions.Restart, null)
                    }
                }
                else -> {}
            }
            ActionButton(onClick = onRemove, tooltip = { Text("Remove") }) {
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
    ) {
        ToolWindowHeader(title = "Views")
        
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            items.forEach { item ->
                SidebarRow(
                    item = item,
                    isSelected = item == selectedItem,
                    onClick = { onItemSelected(item) }
                )
            }
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
    val background = if (isSelected) style.colors.backgroundSelectedActive else Color.Transparent
    val contentColor = if (isSelected) style.colors.contentSelectedActive else Color.Unspecified

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            key = item.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = contentColor
        )
        Text(
            text = item.label,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp),
            color = contentColor
        )
    }
}

@Composable
fun ToolWindowHeader(
    title: String,
    actions: @Composable () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title.uppercase(),
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = JewelTheme.globalColors.text.disabled
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
        Divider(Orientation.Horizontal)
    }
}

@Composable
fun StatusBar(
    info: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(Orientation.Horizontal)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(AllIconsKeys.General.Information, null, modifier = Modifier.size(14.dp))
            Text(
                text = info,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "UTF-8",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp)
            )
            Text(
                text = "Connected",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color(0xFF4CAF50))
            )
        }
    }
}

data class SidebarItem(
    val label: String,
    val icon: IconKey,
    val id: String
)

private fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "Unknown"
    if (bytes == 0L) return "0 B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    if (exp == 0) return "$bytes B"
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
}

private fun formatSpeed(bytesPerSec: Long): String {
    return formatSize(bytesPerSec) + "/s"
}