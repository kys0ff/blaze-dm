package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.util.formatSpeed
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text
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
            tint = BlazeColors.accent,
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
    onDismiss: () -> Unit,
) {
    val strings = blazeStrings // TODO: make use of strings or remove it
    Box(modifier = Modifier.fillMaxSize()) {
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
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = BlazeLayout.StatusBarHeight + 8.dp),
        )
    }
}

@Composable
private fun BackgroundTasksCard(
    running: List<Download>,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
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
                .height(BlazeLayout.ToolWindowHeaderHeight)
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
            ToolbarIconButton(
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
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                running.forEach { download ->
                    BackgroundTaskRow(
                        download = download,
                        onPause = { onPause(download.id) },
                        onCancel = { onCancel(download.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundTaskRow(
    download: Download,
    onPause: () -> Unit,
    onCancel: () -> Unit,
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
            modifier = Modifier.weight(1f),
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
                val label = if (indeterminate) {
                    "\u2014"
                } else {
                    "${(progress * 100).toInt()}%" +
                        (if (isDownloading && download.speed > 0) "  ${formatSpeed(download.speed, strings)}" else "")
                }
                Text(
                    text = label,
                    style = JewelTheme.typography.small,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 1,
                )
            }
        }
        if (download.canPauseFromTasks()) {
            ToolbarIconButton(key = AllIconsKeys.Actions.Pause, tooltip = s.pause, onClick = onPause)
        }
        ToolbarIconButton(key = AllIconsKeys.Actions.Cancel, tooltip = s.cancel, onClick = onCancel)
    }
}

/** A running task is only pausable while it is actively transferring; queued rows just get Cancel. */
private fun Download.canPauseFromTasks(): Boolean = state == DownloadState.DOWNLOADING
