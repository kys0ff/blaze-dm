package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@Composable
fun StatusBar(
    info: String,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true,
    /** Live aggregate throughput across all transferring downloads, as a ready-made label. */
    throughput: String? = null,
    /** A risk worth keeping on screen: colored, unlike the running totals in [info]. */
    alert: String? = null,
    alertIsError: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val strings = blazeStrings
    val small = JewelTheme.typography.small

    Column(modifier = modifier.fillMaxWidth()) {
        Divider(Orientation.Horizontal)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BlazeLayout.StatusBarHeight)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = info,
                style = small,
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
                        tint = BlazeColors.accent,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = throughput,
                        style = small,
                        color = BlazeColors.accent,
                        maxLines = 1,
                    )
                }
            }
            if (alert != null) {
                val alertColor = if (alertIsError) BlazeColors.error else BlazeColors.warning
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
                        .background(if (isConnected) BlazeColors.success else BlazeColors.error)
                )
                Text(
                    text = if (isConnected) strings.common.connected else strings.common.offline,
                    style = small,
                    color = JewelTheme.globalColors.text.info
                )
            }
        }
    }
}
