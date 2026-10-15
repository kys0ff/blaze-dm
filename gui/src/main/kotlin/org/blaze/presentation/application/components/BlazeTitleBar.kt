package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.application.AppShell
import org.blaze.presentation.icons.BlazeFlame
import org.blaze.server.DownloadServiceController
import org.island.shell.IslandMenuCategory
import org.island.shell.IslandTitleBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.koin.compose.koinInject

/**
 * Blaze's title bar - a thin consumer of [IslandTitleBar]. Owns nothing the shell does not already
 * know how to draw; supplies only Blaze's brand vector and Blaze's menu categories (built from
 * Blaze's command bus + server state through [entriesForCategory]).
 *
 * The bar lives inside the window's composition-local providers (see [BlazeWindow]), so its menu
 * tooltips and every shortcut label it prints read the same OS-formatted accelerators as the rest
 * of the chrome.
 */
@Composable
fun DecoratedWindowScope.BlazeTitleBar(shell: AppShell) {
    val strings = blazeStrings
    val commandBus = koinInject<AppCommandBus>()
    // Whether the local API server is up drives the Tools submenu's enabled rows. Labels are
    // static, so the value only affects the (state-reflecting) enabled flags - the measured menu
    // width is unchanged.
    val serverController = koinInject<DownloadServiceController>()
    val serverStatus by serverController.status.collectAsState()
    val serverRunning = serverStatus.running

    val menuCategories = remember(strings, commandBus, serverRunning) {
        TitleBarMenuCategory.entries.map { category ->
            IslandMenuCategory(
                label = categoryLabel(category, strings),
                entries = { onExecuted ->
                    entriesForCategory(category, strings, commandBus, serverRunning, onExecuted)
                },
            )
        }
    }

    IslandTitleBar(
        host = shell,
        brand = {
            Icon(
                imageVector = BlazeFlame,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
        },
        menuCategories = menuCategories,
        leadingCollapsedContent = { SpeedLimitWidget() },
    )
}
