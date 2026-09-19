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
import org.blaze.presentation.theme.IdeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun DownloadState.indicatorColor(): Color = when (this) {
    DownloadState.QUEUED -> JewelTheme.globalColors.text.info
    DownloadState.DOWNLOADING -> IdeColors.accent
    DownloadState.PAUSED -> IdeColors.warning
    DownloadState.COMPLETED -> IdeColors.success
    DownloadState.FAILED -> IdeColors.error
    DownloadState.REMOVING -> JewelTheme.globalColors.text.disabled
}

@Composable
fun StatusBadge(state: DownloadState, modifier: Modifier = Modifier) {
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
            text = state.name.lowercase().replaceFirstChar { it.uppercase() },
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
        )
    }
}
