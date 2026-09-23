package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** How loudly an [AdvisoryChip] asks for attention. */
enum class AdvisoryTone { WARNING, ERROR }

/**
 * A compact inline warning carried by a download row, next to its status badge.
 *
 * Chips state a *condition* rather than an event ("can't resume", "waiting for peers") so a user
 * scanning the list sees the risk before pressing pause, instead of after the progress is gone.
 */
@Composable
fun AdvisoryChip(
    text: String,
    tone: AdvisoryTone,
    modifier: Modifier = Modifier,
) {
    val color: Color = when (tone) {
        AdvisoryTone.WARNING -> BlazeColors.warning
        AdvisoryTone.ERROR -> BlazeColors.error
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            key = AllIconsKeys.General.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The sentence-long sibling of [AdvisoryChip]: an explanation of what a decision costs, used inside
 * dialogs where the user has room to read it before committing.
 */
@Composable
fun AdvisoryCallout(
    text: String,
    tone: AdvisoryTone,
    modifier: Modifier = Modifier,
) {
    val color: Color = when (tone) {
        AdvisoryTone.WARNING -> BlazeColors.warning
        AdvisoryTone.ERROR -> BlazeColors.error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            key = AllIconsKeys.General.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = color
        )
    }
}
