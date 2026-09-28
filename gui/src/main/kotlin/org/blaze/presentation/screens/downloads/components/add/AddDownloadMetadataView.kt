package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.download.api.DownloadPriority
import org.blaze.presentation.screens.downloads.components.AdvisoryCallout
import org.blaze.presentation.screens.downloads.components.AdvisoryTone
import org.blaze.presentation.screens.downloads.components.label
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.util.formatSize
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun AddDownloadMetadataView(
    state: AddDownloadState
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs
    val metadata = state.metadata

    // What is certainly wrong with the destination - checked here rather than in the callers so
    // both the resolved and the pending-metadata review show the same verdict.
    val destinationProblem = when {
        state.destinationStatus.isError -> dStrings.destinationNotWritable
        else -> state.destinationStatus.notEnoughSpaceText(state.announcedBytes, strings)
    }

    // A torrent whose metainfo hasn't arrived yet still deserves a review step: the user chose
    // to download it, so show what *is* known (source, folder, priority), say plainly what is
    // pending, and let Add queue it - the name and size fill themselves in on the row.
    if (metadata == null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            destinationProblem?.let { problem ->
                AdvisoryCallout(problem, AdvisoryTone.ERROR)
            }
            AdvisoryCallout(dStrings.metadataUnresolvedTorrent, AdvisoryTone.WARNING)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                    .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.common.name, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                    Text(state.source, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dStrings.saveTo, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                    Text(state.destination.text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = JewelTheme.globalColors.text.info)
                }
                PriorityPicker(state)
            }

            OutlinedButton(onClick = { state.step = 1 }) {
                Text(dStrings.backToEdit)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = dStrings.metadataResolved,
            style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.common.name, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                Text(metadata.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dStrings.size, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                Text(formatSize(metadata.totalSize, strings))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dStrings.url, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                Text(state.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = JewelTheme.globalColors.text.info)
            }
            // Priority is picked here, next to the resolved name/size, so it's set alongside the
            // other per-download choices rather than buried in a separate step.
            PriorityPicker(state)
        }

        // ── Pre-flight: what is already known to be wrong, said before the transfer rather than after ──
        destinationProblem?.let { problem ->
            AdvisoryCallout(problem, AdvisoryTone.ERROR)
        }
        // A server that will not continue a stopped range turns every later pause into a restart.
        if (metadata.resumeSupported == false) {
            AdvisoryCallout(dStrings.sourceCannotResume, AdvisoryTone.WARNING)
        }

        if (metadata.files != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dStrings.files, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = dStrings.selectAll,
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                            color = JewelTheme.globalColors.text.info,
                            modifier = Modifier.clickable {
                                state.selectedFileIndices = metadata.files.map { it.index }.toSet()
                            }
                        )
                        Text(
                            text = dStrings.deselectAll,
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                            color = JewelTheme.globalColors.text.info,
                            modifier = Modifier.clickable {
                                state.selectedFileIndices = emptySet()
                            }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.3f))
                        .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    items(metadata.files) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.selectedFileIndices = if (state.selectedFileIndices.contains(file.index)) {
                                        state.selectedFileIndices - file.index
                                    } else {
                                        state.selectedFileIndices + file.index
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = state.selectedFileIndices.contains(file.index),
                                onCheckedChange = {
                                    state.selectedFileIndices = if (it) {
                                        state.selectedFileIndices + file.index
                                    } else {
                                        state.selectedFileIndices - file.index
                                    }
                                }
                            )
                            Text(
                                text = file.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatSize(file.size, strings),
                                color = JewelTheme.globalColors.text.info,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { state.step = 1 }) {
            Text(dStrings.backToEdit)
        }
    }
}

/** The four priority levels as inline chips, shared by the resolved and the pending-metadata review. */
@Composable
private fun PriorityPicker(state: AddDownloadState) {
    val strings = blazeStrings
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            strings.downloads.priority.label,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.width(60.dp)
        )
        val p = strings.downloads.priority
        DownloadPriority.entries.forEach { level ->
            val selected = state.priority == level
            Text(
                text = level.label(p),
                color = if (selected) BlazeColors.accent else JewelTheme.globalColors.text.info,
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (selected) BlazeColors.accent.copy(alpha = 0.15f)
                        else Color.Transparent
                    )
                    .clickable { state.priority = level }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
