package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.models.DownloadState
import org.blaze.download.api.DownloadPriority
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeTypography
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun DownloadState.indicatorColor(): Color = when (this) {
    DownloadState.QUEUED -> JewelTheme.globalColors.text.info
    DownloadState.DOWNLOADING -> BlazeColors.accent
    DownloadState.PAUSED -> BlazeColors.warning
    DownloadState.COMPLETED -> BlazeColors.success
    DownloadState.FAILED -> BlazeColors.error
    DownloadState.REMOVING -> JewelTheme.globalColors.text.disabled
    DownloadState.SEEDING -> BlazeColors.warning
}

/** Localized status text for a download state, shared by the badge and the details' dialog. */
fun DownloadState.label(strings: BlazeStrings): String = when (this) {
    DownloadState.QUEUED -> strings.downloads.status.queued
    DownloadState.DOWNLOADING -> strings.downloads.status.downloading
    DownloadState.PAUSED -> strings.downloads.status.paused
    DownloadState.COMPLETED -> strings.downloads.status.completed
    DownloadState.FAILED -> strings.downloads.status.error
    DownloadState.REMOVING -> strings.downloads.status.cancelling
    DownloadState.SEEDING -> strings.downloads.status.seeding
}

@Composable
fun StatusBadge(state: DownloadState, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val strings = blazeStrings
    val text = state.label(strings)
    val indicator = if (dimmed) JewelTheme.globalColors.text.disabled else state.indicatorColor()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(indicator)
        )
        Text(
            text = text,
            color = if (dimmed) JewelTheme.globalColors.text.disabled else Color.Unspecified,
            style = BlazeTypography.badge
        )
    }
}

/**
 * Small chip marking a download whose [priority] stands out from the default. Rendered only for
 * non-[DownloadPriority.NORMAL] rows so the common case stays uncluttered; the accent color echoes
 * urgency so a background transfer reads differently from an urgent one at a glance.
 */
@Composable
fun PriorityChip(priority: DownloadPriority, modifier: Modifier = Modifier) {
    val strings = blazeStrings
    val color = when (priority) {
        DownloadPriority.HIGH -> BlazeColors.success
        DownloadPriority.LOW -> BlazeColors.warning
        DownloadPriority.IDLE -> JewelTheme.globalColors.text.disabled
        DownloadPriority.NORMAL -> JewelTheme.globalColors.text.info
    }
    Text(
        text = priority.label(strings.downloads.priority),
        color = color,
        style = BlazeTypography.badge,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
