package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.theme.BlazeColors
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

/** Localized status text for a download state, shared by the badge and the details dialog. */
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
fun StatusBadge(state: DownloadState, modifier: Modifier = Modifier) {
    val strings = blazeStrings
    val text = state.label(strings)

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
            text = text,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
        )
    }
}
