package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.DownloadCounts
import org.blaze.presentation.screens.downloads.DownloadFilter
import org.blaze.presentation.screens.downloads.DownloadSortField
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val ChipShape = RoundedCornerShape(11.dp)

/**
 * A slim bar under the toolbar holding the two list-narrowing affordances: state [FilterChip]s on the
 * left and the [SortButton] group on the right. Everything scrolls horizontally so a narrow window
 * never clips the controls. All interaction is expressed as [DownloadsEvent]s the screen model owns,
 * keeping this a pure view.
 */
@Composable
fun DownloadsFilterSortBar(
    filter: DownloadFilter,
    counts: DownloadCounts,
    sortField: DownloadSortField,
    sortAscending: Boolean,
    onEvent: (DownloadsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = blazeStrings
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .height(30.dp)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Filters ───────────────────────────────────────────────────────────
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

        // Visual separator between the two control groups.
        Icon(
            key = AllIconsKeys.General.ChevronRight,
            contentDescription = null,
            tint = JewelTheme.globalColors.text.disabled,
            modifier = Modifier.size(12.dp).padding(horizontal = 2.dp),
        )

        // ── Sort ──────────────────────────────────────────────────────────────
        DownloadSortField.entries.forEach { field ->
            SortButton(field, strings.downloads.sorting, sortField, sortAscending) {
                onEvent(DownloadsEvent.SortBy(field))
            }
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
    val background = when {
        selected -> BlazeColors.accent.copy(alpha = 0.22f)
        hovered -> BlazeColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (selected) BlazeColors.accent else JewelTheme.globalColors.text.normal
    Row(
        modifier = Modifier
            .clip(ChipShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp),
                color = if (selected) contentColor else JewelTheme.globalColors.text.info,
            )
        }
    }
}

@Composable
private fun SortButton(
    field: DownloadSortField,
    labels: BlazeStrings.Downloads.Sorting,
    activeField: DownloadSortField,
    ascending: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = field == activeField
    val background = when {
        selected -> BlazeColors.accent.copy(alpha = 0.16f)
        hovered -> BlazeColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (selected) BlazeColors.accent else JewelTheme.globalColors.text.info
    Row(
        modifier = Modifier
            .clip(ChipShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = labels.labelFor(field),
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = contentColor,
            maxLines = 1,
        )
        if (selected) {
            Text(
                text = if (ascending) "\u2191" else "\u2193",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = contentColor,
            )
        }
    }
}

private fun BlazeStrings.Downloads.Sorting.labelFor(field: DownloadSortField): String = when (field) {
    DownloadSortField.NAME -> name
    DownloadSortField.SIZE -> size
    DownloadSortField.SPEED -> speed
    DownloadSortField.STATE -> state
    DownloadSortField.DATE_ADDED -> dateAdded
}
