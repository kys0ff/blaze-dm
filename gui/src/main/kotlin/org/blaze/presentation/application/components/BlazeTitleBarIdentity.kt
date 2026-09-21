package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.blaze.presentation.icons.BlazeFlame
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.utils.clientRegion

@Composable
fun TitleBarScope.BlazeTitleBarIdentity() {
    Row(
        modifier = Modifier
            .align(Alignment.Start)
            .height(40.dp)
            .padding(start = 8.dp)
            .clientRegion("title_bar_left"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            BlazeFlame,
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )

        Text(
            text = "Blaze",
            style = JewelTheme.defaultTextStyle.copy(
                fontWeight = FontWeight.Medium,
            ),
        )

        Spacer(Modifier.width(8.dp))

        Divider(
            Orientation.Vertical,
            modifier = Modifier.height(20.dp),
        )
    }
}