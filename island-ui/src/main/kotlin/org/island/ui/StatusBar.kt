package org.island.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.island.theme.ui.IslandLayout
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTypography
import org.island.ui.text.LocalIslandUiStrings
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The IDE's window-bottom readout: one small-text row carrying a status line, an optional live
 * throughput figure, an optional persistent alert, app-supplied [trailing] content, and a
 * connection dot. It paints nothing itself — the bar sits on the frame layer (flat color +
 * ambient glow), exactly like the reference.
 */
@Composable
fun StatusBar(
    info: String,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true,
    /** Live aggregate throughput, as a ready-made label. */
    throughput: String? = null,
    /** A risk worth keeping on screen: colored, unlike the running totals in [info]. */
    alert: String? = null,
    alertIsError: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val strings = LocalIslandUiStrings.current
    val small = IslandTypography.statusBar
    // The bar is part of the Islands frame, so it paints nothing: the frame layer (flat color +
    // ambient glow) shows through it, exactly like the tool-window stripe.
    val frameBackground = Color.Transparent

    Column(modifier = modifier.fillMaxWidth()) {
        // The frame background and the gap above do the separation, so no hairline divider.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IslandLayout.StatusBarHeight)
                .background(frameBackground)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = info,
                style = small,
                // The IDE's status text is small, regular and *secondary* — it is a readout, never
                // the thing the user is meant to look at first.
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!throughput.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Download,
                        contentDescription = null,
                        tint = IslandSemanticColors.accent,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = throughput,
                        style = small,
                        color = IslandSemanticColors.accent,
                        maxLines = 1,
                    )
                }
            }
            if (alert != null) {
                val alertColor = if (alertIsError) IslandSemanticColors.error else IslandSemanticColors.warning
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        key = AllIconsKeys.General.Warning,
                        contentDescription = null,
                        tint = alertColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = alert,
                        style = small,
                        color = alertColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) IslandSemanticColors.success else IslandSemanticColors.error)
                )
                Text(
                    text = if (isConnected) strings.connected() else strings.offline(),
                    style = small,
                    color = JewelTheme.globalColors.text.info
                )
            }
        }
    }
}
