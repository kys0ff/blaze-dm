package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.util.formatDuration
import org.blaze.presentation.util.formatSpeed
import org.island.theme.ui.IslandLayout
import org.island.theme.ui.IslandSemanticColors
import org.island.ui.toolbar.IslandToolbarIconButton
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * The status-bar Background Tasks widget: a compact, always-visible count of live transfers that
 * opens [BackgroundTasksPopup] when clicked. Hidden entirely when nothing is running, so the bar
 * only carries information that is actionable right now.
 */
@Composable
fun BackgroundTasksStatusItem(
    runningCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runningCount <= 0) return
    val strings = blazeStrings
    val chevron = if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            key = AllIconsKeys.Actions.Download,
            contentDescription = null,
            tint = IslandSemanticColors.accent,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = strings.downloads.backgroundTasks.runningCount(runningCount),
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.normal,
            maxLines = 1,
        )
        Icon(
            key = chevron,
            contentDescription = null,
            tint = JewelTheme.globalColors.text.info,
            modifier = Modifier.size(10.dp),
        )
    }
}

/**
 * A floating card listing every running download with its live progress and per-item pause / cancel
 * actions. Rendered inside a full-window [Box] so it can hover above the content near the status-bar
 * widget that opened it; the transparent [scrim] closes it when the user clicks anywhere outside.
 */
@Composable
fun BackgroundTasksPopup(
    running: List<Download>,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTag = "background_tasks_popup" },
    ) {
        // Transparent click-to-dismiss layer beneath the card.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
        BackgroundTasksCard(
            running = running,
            onPause = onPause,
            onCancel = onCancel,
            onOpenDetails = { id ->
                onOpenDetails(id)
                onDismiss()
            },
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = IslandLayout.StatusBarHeight + 8.dp),
        )
    }
}

@Composable
private fun BackgroundTasksCard(
    running: List<Download>,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = blazeStrings
    val tasks = strings.downloads.backgroundTasks
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .width(360.dp)
            .clip(shape)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IslandLayout.ToolWindowHeaderHeight)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = tasks.title,
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.normal,
                maxLines = 1,
            )
            IslandToolbarIconButton(
                key = AllIconsKeys.General.Close,
                tooltip = strings.common.close,
                onClick = onDismiss,
            )
        }
        Divider(Orientation.Horizontal)

        if (running.isEmpty()) {
            Text(
                text = tasks.empty,
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        } else {
            // Jewel's container owns the vertical scroll (and with it the overlay scrollbar); a bare
            // `.verticalScroll()` here would leave the list scrolling with no indicator at all.
            VerticallyScrollableContainer(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    running.forEach { download ->
                        BackgroundTaskRow(
                            download = download,
                            onPause = { onPause(download.id) },
                            onCancel = { onCancel(download.id) },
                            onOpenDetails = { onOpenDetails(download.id) },
                        )
                    }
                }
            }
            // A totals footer: the whole popup is about live transfers, so it closes with the honest
            // split of what is moving now versus what is still waiting for a slot.
            Divider(Orientation.Horizontal)
            Text(
                text = tasks.footer(
                    active = running.count { it.state == DownloadState.DOWNLOADING },
                    queued = running.count { it.state == DownloadState.QUEUED },
                ),
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BackgroundTaskRow(
    download: Download,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val strings = blazeStrings
    val s = strings.downloads.selection
    val isDownloading = download.state == DownloadState.DOWNLOADING
    val progress = download.progress.coerceIn(0f, 1f)
    val indeterminate = isDownloading && (download.totalSize == null || (download.downloadedSize == 0L && download.speed == 0L))

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onOpenDetails)
                .semantics { testTag = "background_task_${download.id}" },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = download.name,
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (indeterminate) {
                    IndeterminateHorizontalProgressBar(modifier = Modifier.weight(1f).height(4.dp))
                } else {
                    HorizontalProgressBar(progress = progress, modifier = Modifier.weight(1f).height(4.dp))
                }
                val eta = download.eta
                val parts = buildList {
                    if (!indeterminate) add("${(progress * 100).toInt()}%")
                    if (isDownloading && download.speed > 0) add(formatSpeed(download.speed, strings))
                    if (isDownloading && eta != null && eta > 0) add(formatDuration(eta))
                }
                Text(
                    text = if (parts.isEmpty()) "\u2014" else parts.joinToString("  \u00b7  "),
                    style = JewelTheme.typography.small,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 1,
                )
            }
        }
        if (download.canPauseFromTasks()) {
            IslandToolbarIconButton(key = AllIconsKeys.Actions.Pause, tooltip = s.pause, onClick = onPause)
        }
        IslandToolbarIconButton(key = BlazeIcons.Stop, tooltip = s.cancel, onClick = onCancel)
    }
}

/** A running task is only pausable while it is actively transferring; queued rows just get Cancel. */
private fun Download.canPauseFromTasks(): Boolean = state == DownloadState.DOWNLOADING
