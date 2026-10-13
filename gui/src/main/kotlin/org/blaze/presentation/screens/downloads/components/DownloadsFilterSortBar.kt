package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.DownloadCounts
import org.blaze.presentation.screens.downloads.DownloadFilter
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.island.theme.ui.IslandChromeColors
import org.island.theme.ui.IslandLayout
import org.island.theme.ui.IslandTypography
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private val ChipShape = RoundedCornerShape(6.dp)

/**
 * A slim bar under the toolbar holding the list-narrowing state [FilterChip]s. Sorting now lives in
 * the table header ([DownloadsTableHeader]), so this bar is filters-only. Everything scrolls
 * horizontally so a narrow window never clips the controls. All interaction is expressed as
 * [DownloadsEvent]s the screen model owns, keeping this a pure view.
 */
@Composable
fun DownloadsFilterSortBar(
    filter: DownloadFilter,
    counts: DownloadCounts,
    onEvent: (DownloadsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = blazeStrings
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .height(IslandLayout.FilterSortBarHeight)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(strings.downloads.filters.all, counts.total, filter == DownloadFilter.ALL) {
            onEvent(DownloadsEvent.FilterBy(DownloadFilter.ALL))
        }
        FilterChip(strings.downloads.filters.active, counts.downloading + counts.queued, filter == DownloadFilter.ACTIVE) {
            onEvent(DownloadsEvent.FilterBy(DownloadFilter.ACTIVE))
        }
        FilterChip(strings.downloads.filters.completed, counts.completed, filter == DownloadFilter.COMPLETED) {
            onEvent(DownloadsEvent.FilterBy(DownloadFilter.COMPLETED))
        }
        FilterChip(strings.downloads.filters.paused, counts.paused, filter == DownloadFilter.PAUSED) {
            onEvent(DownloadsEvent.FilterBy(DownloadFilter.PAUSED))
        }
        FilterChip(strings.downloads.filters.failed, counts.failed, filter == DownloadFilter.FAILED) {
            onEvent(DownloadsEvent.FilterBy(DownloadFilter.FAILED))
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The IDE's selected tab is a gray rounded rect with a hairline rim and primary text, never a
    // saturated accent fill — an accent pill turns every filter row into a call to action. It uses
    // the same token family as the selected stripe button, so "selected" reads identically wherever
    // the user meets it.
    val background = when {
        selected -> IslandChromeColors.stripeSelected
        hovered -> IslandChromeColors.stripeHover
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .clip(ChipShape)
            .background(background)
            // The rim is the same translucent overlay the stripe family uses for hover: over the gray
            // fill it reads as a lighter edge in dark and a darker one in light, which is exactly
            // what the reference's selected tab shows.
            .border(
                IslandLayout.IslandBorderWidth,
                if (selected) IslandChromeColors.stripeHover else Color.Transparent,
                ChipShape,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = IslandTypography.chip,
            color = JewelTheme.globalColors.text.normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            // Counts are a readout, not part of the label: secondary in both states.
            Text(
                text = count.toString(),
                style = IslandTypography.chip,
                color = JewelTheme.globalColors.text.info,
            )
        }
    }
}
