package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.util.formatDuration
import org.blaze.presentation.util.formatSize
import org.blaze.presentation.util.formatSpeed
import org.blaze.presentation.util.toFriendlyMessage
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val LabelWidth = 120.dp

/**
 * Read-only summary of a single download: identity, transport progress and the on-disk
 * location, plus error state when present. Rows are shown only when they carry information,
 * so a queued HTTP file and a seeding torrent each read cleanly.
 */
@Composable
fun DownloadDetailsDialog(
    download: Download,
    onDismiss: () -> Unit,
) {
    val strings = blazeStrings
    val details = strings.downloads.dialogs.details
    val isActive = download.state == DownloadState.DOWNLOADING || download.state == DownloadState.SEEDING
    val scheduledInFuture = download.scheduledAt?.let { it > System.currentTimeMillis() } == true

    IdeDialog(onDismiss = onDismiss, width = 480.dp) {
        IdeDialogTitle(details.title)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DetailRow(details.name) { Text(download.name) }
            DetailRow(details.status) { StatusBadge(download.state) }

            val total = download.totalSize
            val sizeText = if (total != null) {
                strings.downloads.progress(
                    formatSize(download.downloadedSize, strings),
                    formatSize(total, strings)
                ) + "  (${(download.progress * 100).toInt()}%)"
            } else {
                formatSize(download.downloadedSize, strings)
            }
            DetailRow(details.size) { Text(sizeText) }

            if (isActive && download.speed > 0) {
                DetailRow(details.speed) { Text(formatSpeed(download.speed, strings)) }
            }
            val eta = download.eta
            if (isActive && eta != null) {
                DetailRow(details.eta) { Text(formatDuration(eta)) }
            }
            if (download.peers > 0) {
                DetailRow(details.peers) { Text(strings.downloads.peers(download.peers)) }
            }
            download.selectedFiles?.let { files ->
                DetailRow(details.files) { Text(strings.downloads.filesCount(files.size)) }
            }

            DetailRow(details.source) {
                Text(download.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            DetailRow(details.location) {
                Text(download.savePath, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            DetailRow(details.added) { Text(formatDateTime(download.addedAt)) }
            if (scheduledInFuture) {
                DetailRow(details.scheduled) { Text(formatDateTime(download.scheduledAt)) }
            }

            download.error?.let { error ->
                DetailRow(details.error) {
                    Text(
                        text = error.toFriendlyMessage(strings),
                        color = BlazeColors.error
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DefaultButton(onClick = onDismiss) { Text(strings.common.close) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable RowScope.() -> Unit) {
    val secondary = JewelTheme.globalColors.text.info
    val labelStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = secondary,
            modifier = Modifier.width(LabelWidth)
        )
        Row(modifier = Modifier.fillMaxWidth(), content = value)
    }
}

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
