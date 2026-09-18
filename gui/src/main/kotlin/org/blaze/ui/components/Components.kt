package org.blaze.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.engine.api.DownloadError
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import kotlin.math.ln
import kotlin.math.pow

// ---------------------------------------------------------------------------
// IDE palette
//
// Neutral colors come from the Jewel theme so they follow the active IDE theme.
// Semantic colors (accent / success / warning) aren't exposed by GlobalColors,
// so they use the IntelliJ Platform light/dark values, picked by panel luminance.
// ---------------------------------------------------------------------------

private object IdeColors {
    val isDark: Boolean
        @Composable get() = JewelTheme.globalColors.panelBackground.luminance() < 0.5f

    val accent: Color
        @Composable get() = if (isDark) Color(0xFF548AF7) else Color(0xFF3574F0)

    val success: Color
        @Composable get() = if (isDark) Color(0xFF5FB865) else Color(0xFF208A3C)

    val warning: Color
        @Composable get() = if (isDark) Color(0xFFF2C55C) else Color(0xFFA46704)

    val error: Color
        @Composable get() = JewelTheme.globalColors.text.error

    /** Subtle row hover: a translucent overlay of the text color works in both themes. */
    val hover: Color
        @Composable get() = JewelTheme.globalColors.text.normal.copy(alpha = 0.07f)
}

private val ListItemShape = RoundedCornerShape(6.dp)

@Composable
private fun DownloadState.indicatorColor(): Color = when (this) {
    DownloadState.QUEUED -> JewelTheme.globalColors.text.info
    DownloadState.DOWNLOADING -> IdeColors.accent
    DownloadState.PAUSED -> IdeColors.warning
    DownloadState.COMPLETED -> IdeColors.success
    DownloadState.FAILED -> IdeColors.error
    DownloadState.REMOVING -> JewelTheme.globalColors.text.disabled
}

// ---------------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String) -> Unit
) {
    var url by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    val source = url.text.trim()
    val canAdd = source.isNotEmpty()
    val looksSupported = source.isEmpty() || source.isSupportedSource()

    fun submit() {
        if (canAdd) {
            onAdd(source)
            onDismiss()
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val shape = RoundedCornerShape(8.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add download",
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Download source:")

                TextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://…  or  magnet:?xt=…") },
                    outline = if (looksSupported) Outline.None else Outline.Warning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                submit()
                                true
                            } else {
                                false
                            }
                        }
                )

                // Context help sits under the field, like IDE form hints.
                if (looksSupported) {
                    Text(
                        text = "Supports HTTP(S) links, magnet links and .torrent files.",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                        color = JewelTheme.globalColors.text.info
                    )
                } else {
                    Text(
                        text = "Not an HTTP(S) link, magnet link or .torrent file. It may fail to download.",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                        color = IdeColors.warning
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                DefaultButton(onClick = ::submit, enabled = canAdd) {
                    Text("Add")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Download list
// ---------------------------------------------------------------------------

/** IDE-style status: a small colored dot followed by the state name. */
@Composable
fun StatusBadge(state: DownloadState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(state.indicatorColor())
        )
        Text(
            text = state.name.lowercase().replaceFirstChar { it.uppercase() },
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
        )
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun DownloadRow(
    download: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
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

    // Like IDE lists, row actions appear on hover/selection. Failed rows keep Retry visible.
    val showActions = hovered || isSelected || download.state == DownloadState.FAILED

    // Total size unknown and nothing received yet: fetching metadata / connecting.
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

            // Reserve space so the row content doesn't shift when actions appear.
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
                        ToolbarIconButton(AllIconsKeys.Actions.GC, "Remove", onRemove)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tool window chrome
// ---------------------------------------------------------------------------

/** Icon-only toolbar button with a tooltip, as used in tool window headers and row actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolbarIconButton(
    key: IconKey,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tooltip = { Text(tooltip) }
    ) {
        Icon(key, tooltip)
    }
}

@Composable
fun Sidebar(
    items: List<SidebarItem>,
    selectedItem: SidebarItem,
    onItemSelected: (SidebarItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ToolWindowHeader(title = "Views")

            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items.forEach { item ->
                    SidebarRow(
                        item = item,
                        // Compare by id: `count` changes would break data class equality.
                        isSelected = item.id == selectedItem.id,
                        onClick = { onItemSelected(item) }
                    )
                }
            }
        }
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
    }
}

@Composable
private fun SidebarRow(
    item: SidebarItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val style = JewelTheme.simpleListItemStyle
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        isSelected -> style.colors.backgroundSelectedActive
        hovered -> IdeColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (isSelected) style.colors.contentSelectedActive else Color.Unspecified
    val countColor = if (isSelected) style.colors.contentSelectedActive else JewelTheme.globalColors.text.info

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(ListItemShape)
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp),
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
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            item.count?.let { count ->
                Text(
                    text = count.toString(),
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                    color = countColor
                )
            }
        }
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
                .height(32.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                actions()
            }
        }
        Divider(Orientation.Horizontal)
    }
}

@Composable
fun StatusBar(
    info: String,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val small = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    Column(modifier = modifier.fillMaxWidth()) {
        Divider(Orientation.Horizontal)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = info,
                style = small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailing()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) IdeColors.success else IdeColors.error)
                )
                Text(
                    text = if (isConnected) "Connected" else "Offline",
                    style = small,
                    color = JewelTheme.globalColors.text.info
                )
            }
        }
    }
}

data class SidebarItem(
    val label: String,
    val icon: IconKey,
    val id: String,
    /** Optional trailing count, e.g. number of downloads in this view. */
    val count: Int? = null
)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun String.isSupportedSource(): Boolean {
    val s = trim().lowercase()
    return s.startsWith("http://") ||
            s.startsWith("https://") ||
            s.startsWith("magnet:") ||
            s.endsWith(".torrent")
}

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

private fun DownloadError.toFriendlyMessage(): String = when (this) {
    DownloadError.NetworkUnavailable -> "Network unavailable"
    DownloadError.Timeout -> "Connection timed out"
    DownloadError.Unauthorized -> "Unauthorized access"
    DownloadError.NotFound -> "File not found"
    DownloadError.DiskFull -> "Disk full"
    DownloadError.RangeUnsupported -> "Resuming not supported"
    DownloadError.InvalidTorrent -> "Invalid torrent file"
    DownloadError.MetadataTimeout -> "Failed to fetch metadata"
    DownloadError.Cancelled -> "Download cancelled"
    is DownloadError.NetworkFailure -> "Network failure: $message"
    is DownloadError.FileSystemError -> "Disk error: $message"
    is DownloadError.Unknown -> "Unknown error: $message"
}