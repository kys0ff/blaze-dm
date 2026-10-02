package org.blaze.presentation.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.flow.drop
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.application.components.AboutDialog
import org.blaze.presentation.components.BackgroundTasksPopup
import org.blaze.presentation.components.BackgroundTasksStatusItem
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.Island
import org.blaze.presentation.components.SidebarItem
import org.blaze.presentation.components.StatusBar
import org.blaze.presentation.components.ToolWindowStripe
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsScreen
import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.blaze.presentation.screens.downloads.aggregateSpeed
import org.blaze.presentation.screens.downloads.attention
import org.blaze.presentation.screens.extensions.ExtensionsPanel
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.presentation.util.formatSpeed
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.koin.compose.koinInject

class MainScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        val downloads = state.downloads
        val strings = blazeStrings
        val commandBus = koinInject<AppCommandBus>()
        val searchController = koinInject<org.blaze.presentation.search.SearchController>()
        var showAbout by remember { mutableStateOf(false) }
        // The Server Logs tool window: its own screen model owns visibility and the docked layout, the
        // shared repository supplies the live log stream, and the controller is read only for the header's
        // status word (its lifecycle actions go through the command bus so the shell owns the launcher).
        val logModel = koinScreenModel<org.blaze.presentation.screens.serverlog.ServerLogWindowModel>()
        val logState by logModel.state.collectAsState()
        val logRepository = koinInject<org.blaze.logging.ServerLogRepository>()
        val serverController = koinInject<org.blaze.server.DownloadServiceController>()

        // Live transfers surfaced in the status bar; QUEUED counts too so the popup shows the whole
        // pending set, while a row's own pause button is only offered while it is actually moving.
        val running = downloads.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
        }
        var showTasks by remember { mutableStateOf(false) }

        Navigator(DownloadsScreen()) { navigator ->
            // The title-bar chrome raises dialog commands it cannot serve itself: About shows a dialog
            // that lives on this screen, where the dialog state exists. Settings is not one of them - it
            // is a modal window the application shell owns. The view-scoped commands (new download,
            // focus search, toggle details) are handled lower down by the downloads screen itself.
            LaunchedEffect(commandBus) {
                commandBus.commands.collect { command ->
                    when (command) {
                        AppCommand.ShowAbout -> showAbout = true
                        AppCommand.ShowServerLog -> logModel.toggleVisible()
                        else -> Unit
                    }
                }
            }
            // The frame (flat color + ambient glow) is painted once, by BlazeContent, so the
            // chrome and every gap in this screen leak the same continuous field. Painting a solid
            // background here would bury it.
            Box(
                modifier = Modifier
                    .fillMaxSize(),
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
                            label = strings.extensions.title,
                            icon = AllIconsKeys.Nodes.Plugin,
                            id = "extensions",
                        ),
                    )

                    val currentScreen = navigator.lastItem
                    // Downloads and Extensions are the two views the stripe switches between. Settings is
                    // not a stripe item any more - it is a modal window reached from the File menu and
                    // Search Everywhere - so opening it never changes the selection.
                    val selectedItem =
                        if (currentScreen is ExtensionsPanel) sidebarItems[1] else sidebarItems[0]

                    ToolWindowStripe(
                        items = sidebarItems,
                        selectedId = selectedItem.id,
                        onItemSelected = { item ->
                            when (item.id) {
                                "downloads" -> if (currentScreen !is DownloadsScreen) navigator.replaceAll(
                                    DownloadsScreen()
                                )

                                "extensions" -> if (currentScreen !is ExtensionsPanel) navigator.replaceAll(
                                    ExtensionsPanel()
                                )
                            }
                        }
                    )

                    // The content floats on the frame with a gap on every side; the Server Logs tool window
                    // docks beside or under it exactly like the Details pane (its View Mode / Move To /
                    // Resize drive the split), or floats over it when undocked.
                    val contentSlot: @Composable () -> Unit = {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) { navigator.lastItem.Content() }
                    }
                    val logSlot: @Composable () -> Unit = {
                        Island(modifier = Modifier.fillMaxSize()) {
                            org.blaze.presentation.screens.serverlog.ServerLogPanel(
                                model = logModel,
                                repository = logRepository,
                                controller = serverController,
                                commandBus = commandBus,
                                onHide = { logModel.setVisible(false) },
                            )
                        }
                    }
                    val areaModifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(BlazeLayout.IslandGap)
                    val logMoveTo = logState.moveTo
                    when {
                        !logState.visible -> Box(areaModifier) { contentSlot() }

                        logState.viewMode.isDocked -> {
                            // splitFraction is the content's share; a left-docked log window is first, so
                            // the split's first-pane fraction is the content's complement.
                            val logFirst = logMoveTo == DetailsMoveTo.LEFT_TOP || logMoveTo == DetailsMoveTo.LEFT_BOTTOM
                            val firstFraction = if (logFirst) 1f - logState.splitFraction else logState.splitFraction
                            val splitState = remember(logMoveTo) { SplitLayoutState(firstFraction) }
                            LaunchedEffect(firstFraction, splitState) { splitState.dividerPosition = firstFraction }
                            LaunchedEffect(splitState, logMoveTo) {
                                snapshotFlow { splitState.dividerPosition }
                                    .drop(1)
                                    .collect { d -> logModel.setSplitFraction(if (logFirst) 1f - d else d) }
                            }
                            val first: @Composable () -> Unit = if (logFirst) logSlot else contentSlot
                            val second: @Composable () -> Unit = if (logFirst) contentSlot else logSlot
                            val gapDivider = DividerStyle(
                                color = Color.Transparent,
                                metrics = DividerMetrics(thickness = BlazeLayout.IslandGap, startIndent = 0.dp),
                            )
                            if (logMoveTo.isHorizontal) {
                                HorizontalSplitLayout(
                                    first = first,
                                    second = second,
                                    modifier = areaModifier,
                                    dividerStyle = gapDivider,
                                    firstPaneMinWidth = 240.dp,
                                    secondPaneMinWidth = 240.dp,
                                    draggableWidth = BlazeLayout.SplitDragWidth,
                                    state = splitState,
                                )
                            } else {
                                VerticalSplitLayout(
                                    first = first,
                                    second = second,
                                    modifier = areaModifier,
                                    dividerStyle = gapDivider,
                                    firstPaneMinWidth = 160.dp,
                                    secondPaneMinWidth = 120.dp,
                                    draggableWidth = BlazeLayout.SplitDragWidth,
                                    state = splitState,
                                )
                            }
                        }

                        else -> {
                            // Floating: the content fills the area and the log window hovers its chosen edge.
                            val overlayAlignment = when (logMoveTo) {
                                DetailsMoveTo.LEFT_TOP -> Alignment.TopStart
                                DetailsMoveTo.LEFT_BOTTOM -> Alignment.BottomStart
                                DetailsMoveTo.RIGHT_TOP -> Alignment.TopEnd
                                DetailsMoveTo.RIGHT_BOTTOM -> Alignment.BottomEnd
                                DetailsMoveTo.BOTTOM_LEFT -> Alignment.BottomStart
                                DetailsMoveTo.BOTTOM_RIGHT -> Alignment.BottomEnd
                            }
                            Box(modifier = areaModifier) {
                                contentSlot()
                                Box(
                                    modifier = Modifier
                                        .align(overlayAlignment)
                                        .padding(12.dp)
                                        .fillMaxWidth(0.5f)
                                        .fillMaxHeight(if (logMoveTo.isBottom) 0.42f else 0.62f),
                                ) { logSlot() }
                            }
                        }
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
                            // Small, regular, secondary-adjacent: the status bar never carries weight.
                            val snailStyle = BlazeTypography.statusBar
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    key = BlazeIcons.SnailMode,
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
                    // A row click routes through the very activator the downloads screen registers for
                    // Search Everywhere, so "jump to this download" behaves identically from both places.
                    onOpenDetails = { id -> searchController.activateDownload(id) },
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
