package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.download.api.HlsVariantDto
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.util.formatSize
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTypography
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The review step shown once an `.m3u8` link has been inspected and confirmed a playable stream
 * (Phase 2/3). It lists the master playlist's renditions so the user can pick a quality, reports the
 * engine's size estimate for the pick, and names the destination — the assembled media file, never the
 * playlist itself.
 *
 * A media playlist with no advertised variants has nothing to choose, so only the default line shows.
 */
@Composable
fun AddDownloadHlsReviewView(state: AddDownloadState) {
    val strings = blazeStrings
    val d = strings.downloads.dialogs
    val hls = d.hls
    val inspection = state.hlsInspection ?: return

    // Bandwidth is bits/second; Mbps is the unit a viewer recognises as "quality".
    fun bandwidthMbps(variant: HlsVariantDto): String? =
        variant.bandwidth?.let { "%.1f Mbps".format(it / 1_000_000.0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = hls.qualityTitle, style = IslandTypography.emphasizedHeader)

        if (inspection.variants.isEmpty()) {
            // A single media playlist: there is exactly one rendition, so state that plainly.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                    .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(hls.defaultQuality)
            }
        } else {
            val listState = rememberLazyListState()
            VerticallyScrollableContainer(
                scrollState = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.3f))
                    .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp)),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    items(inspection.variants) { variant ->
                        val selected = variant.index == state.selectedHlsVariant
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { state.selectedHlsVariant = variant.index }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = variant.name ?: hls.variantLabel(variant.resolution, bandwidthMbps(variant)),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) IslandSemanticColors.accent else JewelTheme.globalColors.text.normal,
                            )
                            variant.language?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    strings.downloads.dialogs.size,
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.width(90.dp)
                )
                // The estimate is bitrate x duration for the chosen variant; null until it can be worked out.
                Text(
                    inspection.estimatedTotalBytes
                        ?.let { "${hls.estimatedSize}: ${formatSize(it, strings)}" }
                        ?: hls.noSizeEstimate,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.url, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(90.dp))
                SelectionContainer {
                    Text(
                        state.source,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = JewelTheme.globalColors.text.info
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.saveTo, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(90.dp))
                Text(
                    state.destination.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = JewelTheme.globalColors.text.info
                )
            }
        }

        OutlinedButton(onClick = { state.step = 1 }) {
            Text(d.backToEdit)
        }
    }
}
