package org.blaze.presentation.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BackgroundTasksPopup
import org.blaze.presentation.components.BackgroundTasksStatusItem
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.LocalShortcutLabels
import org.blaze.presentation.components.SidebarItem
import org.blaze.presentation.components.ToolWindowStripe
import org.blaze.presentation.components.StatusBar
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.application.components.AboutDialog
import org.blaze.presentation.screens.downloads.aggregateSpeed
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeIslandColors
import org.blaze.presentation.util.formatSpeed
import org.blaze.presentation.screens.downloads.attention
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsScreen
import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.blaze.presentation.screens.settings.SettingsScreen
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.koin.compose.koinInject

class MainScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        val downloads = state.downloads
        val strings = blazeStrings
        val shortcuts = LocalShortcutLabels.current
        val commandBus = koinInject<AppCommandBus>()
        var showAbout by remember { mutableStateOf(false) }

        // Live transfers surfaced in the status bar; QUEUED counts too so the popup shows the whole
        // pending set, while a row's own pause button is only offered while it is actually moving.
        val running = downloads.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
        }
        var showTasks by remember { mutableStateOf(false) }

        Navigator(DownloadsScreen()) { navigator ->
            // The title-bar chrome raises navigation / dialog commands it cannot serve itself: the
            // gear opens Settings and About shows the dialog, both of which live on this screen where
            // the navigator and dialog state exist. The view-scoped commands (new download, focus
            // search, toggle details) are handled lower down by the downloads screen itself.
            LaunchedEffect(navigator, commandBus) {
                commandBus.commands.collect { command ->
                    when (command) {
                        AppCommand.OpenSettings ->
                            if (navigator.lastItem !is SettingsScreen) navigator.replaceAll(SettingsScreen())
                        AppCommand.ShowAbout -> showAbout = true
                        else -> Unit
                    }
                }
            }
            val frameBackground =
                if (BlazeLayout.useIslands) BlazeIslandColors.frame else JewelTheme.globalColors.panelBackground
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(frameBackground),
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    val sidebarItems = listOf(
                        SidebarItem(
                            label = strings.downloads.title,
                            icon = AllIconsKeys.Actions.Download,
                            id = "downloads",
                            count = downloads.size.takeIf { it > 0 }
                        ),
                        SidebarItem(
                            label = strings.settings.title,
                            icon = AllIconsKeys.General.Settings,
                            id = "settings",
                            shortcut = shortcuts.settings,
                            pinned = true,
                        )
                    )

                    val currentScreen = navigator.lastItem
                    val selectedItem = when (currentScreen) {
                        is DownloadsScreen -> sidebarItems[0]
                        is SettingsScreen -> sidebarItems[1]
                        else -> sidebarItems[0]
                    }

                    ToolWindowStripe(
                        items = sidebarItems,
                        selectedId = selectedItem.id,
                        onItemSelected = { item ->
                            when (item.id) {
                                "downloads" -> if (currentScreen !is DownloadsScreen) navigator.replaceAll(
                                    DownloadsScreen()
                                )

                                "settings" -> if (currentScreen !is SettingsScreen) navigator.replaceAll(
                                    SettingsScreen()
                                )
                            }
                        }
                    )

                    // Islands: the content floats on the frame with a gap on every side; the frame
                    // shows through. Classic: the content fills edge to edge with no gap.
                    val contentPadding = if (BlazeLayout.useIslands) BlazeLayout.IslandGap else 0.dp
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(contentPadding),
                    ) {
                        navigator.lastItem.Content()
                    }
                }

                StatusBar(
                    // When more than one row is selected the left message reports the selection, so the
                    // bulk actions always have a visible count to explain what they will act on.
                    info = if (state.selectedIds.size > 1) {
                        strings.downloads.selection.count(state.selectedIds.size)
                    } else {
                        downloads.toStatusSummary(strings)
                    },
                    throughput = downloads.aggregateSpeed().takeIf { it > 0 }
                        ?.let { formatSpeed(it, strings) },
                    alert = downloads.toStatusAlert(strings),
                    trailing = {
                        BackgroundTasksStatusItem(
                            runningCount = running.size,
                            expanded = showTasks,
                            onClick = { showTasks = !showTasks },
                        )
                        if (state.snailModeEnabled) {
                            val snailStyle = JewelTheme.typography.small
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    key = AllIconsKeys.Actions.Suspend,
                                    contentDescription = null,
                                    tint = BlazeColors.warning,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = strings.statusSummary.snailMode("${state.snailSpeedKbps} KB/s"),
                                    style = snailStyle,
                                    color = BlazeColors.warning,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                )
            }

            if (showTasks) {
                BackgroundTasksPopup(
                    running = running,
                    onPause = { id -> screenModel.onEvent(DownloadsEvent.Pause(id)) },
                    onCancel = { id -> screenModel.onEvent(DownloadsEvent.Cancel(id)) },
                    onDismiss = { showTasks = false },
                )
            }
            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }
            }
        }
    }
}

fun List<Download>.toStatusSummary(strings: BlazeStrings): String {
    val list = this
    val parts = buildList {
        val downloading = list.count { it.state == DownloadState.DOWNLOADING }
        val queued = list.count { it.state == DownloadState.QUEUED }
        val paused = list.count { it.state == DownloadState.PAUSED }
        val failed = list.count { it.state == DownloadState.FAILED }

        if (downloading > 0) add(strings.statusSummary.downloading(downloading))
        if (queued > 0) add(strings.statusSummary.queued(queued))
        if (paused > 0) add(strings.statusSummary.paused(paused))
        if (failed > 0) add(strings.statusSummary.failed(failed))
    }

    return if (parts.isEmpty()) strings.statusSummary.ready else parts.joinToString(", ")
}

/**
 * The one risk from the whole list that stays on screen: something the running totals in
 * [toStatusSummary] do not say, and that a user could easily pay for by clicking pause.
 */
fun List<Download>.toStatusAlert(strings: BlazeStrings): String? {
    val attention = attention()
    val advisories = strings.downloads.advisories
    return when {
        attention.cannotResume > 0 -> advisories.alertCannotResume(attention.cannotResume)
        attention.waitingForPeers > 0 -> advisories.alertWaitingPeers(attention.waitingForPeers)
        else -> null
    }
}
