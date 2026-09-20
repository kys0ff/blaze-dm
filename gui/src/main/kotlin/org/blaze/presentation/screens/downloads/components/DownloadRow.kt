package org.blaze.presentation.screens.downloads.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.blazeStrings
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
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

private val ListItemShape = RoundedCornerShape(6.dp)
private val RowMinHeight = 52.dp
private val LeadingIconSize = 16.dp

/**
 * Fixed slot for the trailing area so the text column never re-measures when the hover
 * actions appear (max 4 buttons: files toggle, pause/resume/retry, cancel, remove).
 * Tune to your ToolbarIconButton size.
 */
private val TrailingWidth = 104.dp

private const val META_SEPARATOR = " · "

private fun DownloadState.iconKey(): IconKey = when (this) {
    DownloadState.COMPLETED -> AllIconsKeys.FileTypes.Archive
    DownloadState.FAILED -> AllIconsKeys.General.Error
    else -> AllIconsKeys.Actions.Download
}

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
    onSelect: () -> Unit = {},
    hideResume: Boolean = false
) {
    var showFileList by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val listColors = JewelTheme.simpleListItemStyle.colors
    val strings = blazeStrings

    val files = download.selectedFiles.orEmpty()
    val hasFiles = files.isNotEmpty()

    // ── Colors ────────────────────────────────────────────────────────────────
    val background = when {
        isSelected -> listColors.backgroundSelectedActive
        hovered -> IdeColors.hover
        else -> Color.Transparent
    }
    val primary = if (isSelected) listColors.contentSelectedActive else Color.Unspecified
    val secondary =
        if (isSelected) listColors.contentSelectedActive else JewelTheme.globalColors.text.info

    // ── Derived state ─────────────────────────────────────────────────────────
    val isFailed = download.state == DownloadState.FAILED
    val isCompleted = download.state == DownloadState.COMPLETED
    val isIndeterminate = download.state == DownloadState.DOWNLOADING &&
            download.totalSize == null && download.progress <= 0f
    val progress = download.progress.coerceIn(0f, 1f)
    val showActions = hovered || isSelected || isFailed

    val meta = buildList {
        if (hasFiles) add("${files.size} files") // TODO: move to blazeStrings
        val totalSize = download.totalSize
        add(
            if (totalSize != null) {
                strings.downloads.progress(
                    formatSize(download.downloadedSize, strings),
                    formatSize(totalSize, strings)
                )
            } else {
                formatSize(download.downloadedSize, strings)
            }
        )
        if (download.state == DownloadState.DOWNLOADING) {
            add(strings.downloads.speed(formatSpeed(download.speed, strings)))
        }
        if (download.peers > 0) add(strings.downloads.peers(download.peers))
    }.joinToString(META_SEPARATOR)

    val smallText = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .clip(ListItemShape)
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                key = download.state.iconKey(),
                contentDescription = null,
                modifier = Modifier.size(LeadingIconSize)
            )

            // ── Text column ───────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.name,
                    color = primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // Completed items are done: an IDE wouldn't keep a full progress bar around.
                if (!isCompleted) {
                    Spacer(Modifier.height(4.dp))
                    if (isIndeterminate) {
                        IndeterminateHorizontalProgressBar(
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    } else {
                        HorizontalProgressBar(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusBadge(download.state)
                    Text(
                        text = meta,
                        style = smallText,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (hasFiles) Modifier.clickable { showFileList = !showFileList }
                                else Modifier
                            )
                    )
                    if (isFailed) {
                        download.error?.let { error ->
                            Text(
                                text = error.toFriendlyMessage(strings),
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

            // ── Trailing slot: percent when idle, actions on hover/selection ──
            Box(
                modifier = Modifier.width(TrailingWidth),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (showActions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hasFiles) {
                            ToolbarIconButton(
                                if (showFileList) AllIconsKeys.General.ChevronDown
                                else AllIconsKeys.General.ChevronRight,
                                "Show files", // TODO: move to blazeStrings
                                { showFileList = !showFileList }
                            )
                        }
                        when (download.state) {
                            DownloadState.DOWNLOADING ->
                                ToolbarIconButton(AllIconsKeys.Actions.Pause, strings.downloads.actions.pause, onPause)

                            DownloadState.PAUSED, DownloadState.QUEUED -> {
                                if (!hideResume || download.state != DownloadState.QUEUED) {
                                    ToolbarIconButton(AllIconsKeys.Actions.Resume, strings.downloads.actions.resume, onResume)
                                }
                            }

                            DownloadState.FAILED ->
                                ToolbarIconButton(AllIconsKeys.Actions.Restart, strings.downloads.actions.retry, onRetry)

                            else -> {}
                        }
                        if (download.state == DownloadState.DOWNLOADING ||
                            download.state == DownloadState.PAUSED ||
                            download.state == DownloadState.QUEUED
                        ) {
                            ToolbarIconButton(AllIconsKeys.Actions.Cancel, strings.downloads.actions.cancel, onCancel)
                        }
                        ToolbarIconButton(AllIconsKeys.Actions.GC, strings.downloads.actions.remove, onRemove)
                    }
                } else if (!isCompleted && !isIndeterminate) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = smallText,
                        color = secondary
                    )
                }
            }
        }

        // ── Expandable file list (now laid out *below* the row, not on top of it) ──
        AnimatedVisibility(
            visible = showFileList && hasFiles,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            val panelShape = ListItemShape
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 10.dp, top = 2.dp, bottom = 6.dp)
                    .heightIn(max = 220.dp) // torrents can contain thousands of files
                    .clip(panelShape)
                    .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                    .border(1.dp, JewelTheme.globalColors.borders.normal, panelShape),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(files) { file ->
                    // The panel sits outside the selected row, so always use the theme's
                    // "info" color here — `secondary` turns white when selected.
                    val dim = JewelTheme.globalColors.text.info
                    val path = file.path.replace('\\', '/')
                    val name = path.substringAfterLast('/')
                    val dir = path.substringBeforeLast('/', "")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // IDE style: file name first, parent path dimmed after it.
                        Text(
                            text = buildAnnotatedString {
                                append(name)
                                if (dir.isNotEmpty()) {
                                    withStyle(SpanStyle(color = dim)) { append("  $dir") }
                                }
                            },
                            style = smallText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatSize(file.size, strings),
                            style = smallText,
                            color = dim
                        )
                    }
                }
            }
        }
    }
}