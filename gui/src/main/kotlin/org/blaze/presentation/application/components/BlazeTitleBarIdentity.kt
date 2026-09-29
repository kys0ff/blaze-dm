package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.icons.BlazeFlame
import org.blaze.presentation.theme.BlazeTypography
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.utils.clientRegion
import org.koin.compose.koinInject

/**
 * The IDE's title-bar identity and action clusters, laid out through [TitleBarScope.align]:
 *
 * - Start: a hamburger main-menu affordance (Windows/Linux only — macOS keeps its native bar), then
 *   the app icon and name in a medium weight (never bold), then the one real, backed IDE widget (the
 *   Snail-Mode speed selector). The bar itself is a flat frame color (see BlazeTheme's title-bar
 *   style) — no ambient gradient, no trailing divider.
 * - End: the search and settings icons. Jewel places end-aligned content to the left of the
 *   auto-emitted window controls, so the order reads search, gear, then controls.
 *
 * Both clusters live inside the window's composition-local providers (see [BlazeTitleBar]), so the
 * tooltips and menu rows resolve the same OS-formatted accelerators as the rest of the chrome.
 */
@Composable
fun TitleBarScope.BlazeTitleBarIdentity() {
    val commandBus = koinInject<AppCommandBus>()

    Row(
        modifier = Modifier
            .align(Alignment.Start)
            .height(40.dp)
            .padding(start = 8.dp)
            .clientRegion("title_bar_left"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HamburgerMenuButton(commandBus)

        Icon(
            BlazeFlame,
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )

        Text(
            text = "Blaze",
            style = BlazeTypography.titleBarLabel,
        )

        SpeedLimitWidget()
    }

    Row(
        modifier = Modifier
            .align(Alignment.End)
            .height(40.dp)
            .padding(end = 8.dp)
            .clientRegion("title_bar_right"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleBarTrailingIcons(commandBus)
    }
}
