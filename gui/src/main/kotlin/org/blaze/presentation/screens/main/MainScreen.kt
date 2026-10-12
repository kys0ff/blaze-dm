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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.components.AboutDialog
import org.blaze.presentation.components.BackgroundTasksPopup
import org.blaze.presentation.components.BackgroundTasksStatusItem
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.SidebarItem
import org.blaze.presentation.components.StatusBar
import org.blaze.presentation.components.ToolWindowStripe
import org.blaze.presentation.components.panel.ToolWindowLayoutAction
import org.blaze.presentation.components.panel.ToolWindowLayoutHost
import org.blaze.presentation.components.panel.ToolWindowSpec
import org.blaze.presentation.screens.downloads.DetailsPanelScreenModel
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.DownloadsScreen
import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.blaze.presentation.screens.downloads.aggregateSpeed
import org.blaze.presentation.screens.downloads.attention
import org.blaze.presentation.screens.downloads.components.DownloadDetailsPanel
import org.blaze.presentation.screens.extensions.ExtensionsPanel
import org.blaze.presentation.screens.extensions.ExtensionsWindowModel
import org.blaze.presentation.screens.serverlog.ServerLogPanel
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import org.blaze.presentation.search.SearchController
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.presentation.util.formatSpeed
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.koin.compose.koinInject

class MainScreen : Screen {
    @Composable
    override fun Content() {
        val shellModel = koinScreenModel<MainScreenModel>()
        val shell by shellModel.state.collectAsState()

        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        val downloads = state.downloads
        val strings = blazeStrings
        val searchController = koinInject<SearchController>()
        val logModel = koinScreenModel<ServerLogWindowModel>()
        val logState by logModel.state.collectAsState()

        val extModel = koinScreenModel<ExtensionsWindowModel>()
        val extState by extModel.state.collectAsState()

        val detailsModel = koinScreenModel<DetailsPanelScreenModel>()
        val detailsState by detailsModel.state.collectAsState()

        val running = shell.running

        Navigator(DownloadsScreen()) { navigator ->
            LaunchedEffect(shellModel, searchController) {
                shellModel.effects.collect { effect ->
                    when (effect) {
                        is MainEffect.JumpToDownload -> searchController.activateDownload(effect.id)
                    }
                }
            }

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
                                id = MainToolWindowId.DOWNLOADS.stripeId,
                                count = downloads.size.takeIf { it > 0 }
                            ),
                            SidebarItem(
                                label = strings.extensions.title,
                                icon = AllIconsKeys.Nodes.Plugin,
                                id = MainToolWindowId.EXTENSIONS.stripeId,
                            ),
                        )

                        val currentScreen = navigator.lastItem

                        ToolWindowStripe(
                            items = sidebarItems,
                            selectedId = shell.activeToolWindow.stripeId,
                            onItemSelected = { item ->
                                when (item.id) {
                                    MainToolWindowId.DOWNLOADS.stripeId ->
                                        shellModel.onEvent(
                                            MainEvent.SelectToolWindow(
                                                MainToolWindowId.DOWNLOADS
                                            )
                                        )

                                    MainToolWindowId.EXTENSIONS.stripeId ->
                                        shellModel.onEvent(
                                            MainEvent.SelectToolWindow(
                                                MainToolWindowId.EXTENSIONS
                                            )
                                        )
                                }
                            }
                        )

                        val selectedIds = state.selectedIds
                        val detailsDownload: Download? =
                            if (selectedIds.size == 1) downloads.find { it.id == selectedIds.first() } else null
                        val detailsShown =
                            detailsState.visible && (detailsDownload != null || (!detailsState.viewMode.autoHidesWhenEmpty))

                        val panels = buildList {
                            if (logState.visible) {
                                add(
                                    ToolWindowSpec(
                                        id = "server_log",
                                        title = strings.serverLog.title,
                                        visible = true,
                                        viewMode = logState.viewMode,
                                        moveTo = logState.moveTo,
                                        splitFraction = logState.splitFraction,
                                        innerSplitFraction = logState.innerSplitFraction,
                                        floatingOffset = Offset(
                                            logState.floatingOffsetX,
                                            logState.floatingOffsetY
                                        ),
                                        floatingSize = DpSize(
                                            logState.floatingWidthDp.dp,
                                            logState.floatingHeightDp.dp
                                        ),
                                        onMoveTo = {
                                            logModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetMoveTo(
                                                    it
                                                )
                                            )
                                        },
                                        onViewMode = {
                                            logModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetViewMode(
                                                    it
                                                )
                                            )
                                        },
                                        onSplitFractionChange = {
                                            logModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetSplitFraction(it)
                                            )
                                        },
                                        onInnerSplitFractionChange = {
                                            logModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetInnerSplitFraction(it)
                                            )
                                        },
                                        onFloatingBoundsChange = { offset, size ->
                                            logModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetFloatingBounds(
                                                    offset.x,
                                                    offset.y,
                                                    size.width.value,
                                                    size.height.value
                                                )
                                            )
                                        },
                                        onResize = { logModel.resize(it) },
                                        onClose = { logModel.setVisible(false) },
                                        content = {
                                            ServerLogPanel(
                                                model = logModel,
                                                onHide = { logModel.setVisible(false) },
                                            )
                                        }
                                    )
                                )
                            }

                            if (currentScreen is DownloadsScreen && detailsShown) {
                                add(
                                    ToolWindowSpec(
                                        id = "download_details",
                                        title = detailsDownload?.name
                                            ?: strings.downloads.dialogs.details.title,
                                        visible = true,
                                        viewMode = detailsState.viewMode,
                                        moveTo = detailsState.moveTo,
                                        splitFraction = detailsState.splitFraction,
                                        innerSplitFraction = detailsState.innerSplitFraction,
                                        floatingOffset = Offset(
                                            detailsState.floatingOffsetX,
                                            detailsState.floatingOffsetY
                                        ),
                                        floatingSize = DpSize(
                                            detailsState.floatingWidthDp.dp,
                                            detailsState.floatingHeightDp.dp
                                        ),
                                        onMoveTo = {
                                            detailsModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetMoveTo(it)
                                            )
                                        },
                                        onViewMode = {
                                            detailsModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetViewMode(it)
                                            )
                                        },
                                        onSplitFractionChange = {
                                            detailsModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetSplitFraction(it)
                                            )
                                        },
                                        onInnerSplitFractionChange = {
                                            detailsModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetInnerSplitFraction(it)
                                            )
                                        },
                                        onFloatingBoundsChange = { offset, size ->
                                            detailsModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetFloatingBounds(
                                                    offset.x,
                                                    offset.y,
                                                    size.width.value,
                                                    size.height.value
                                                )
                                            )
                                        },
                                        onResize = { detailsModel.resize(it) },
                                        onClose = { detailsModel.setVisible(false) },
                                        content = {
                                            DownloadDetailsPanel(
                                                download = detailsDownload,
                                                onHide = { detailsModel.setVisible(false) },
                                                modifier = Modifier.fillMaxSize(),
                                                viewMode = detailsState.viewMode,
                                                moveTo = detailsState.moveTo,
                                                groupTabs = detailsState.groupTabs,
                                                onViewMode = {
                                                    detailsModel.onLayoutEvent(
                                                        ToolWindowLayoutAction.SetViewMode(it)
                                                    )
                                                },
                                                onMoveTo = {
                                                    detailsModel.onLayoutEvent(
                                                        ToolWindowLayoutAction.SetMoveTo(it)
                                                    )
                                                },
                                                onResize = { detailsModel.resize(it) },
                                                onToggleGroupTabs = { detailsModel.setGroupTabs(it) },
                                            )
                                        }
                                    )
                                )
                            }

                            if (extState.visible) {
                                add(
                                    ToolWindowSpec(
                                        id = "extensions",
                                        title = strings.extensions.title,
                                        visible = true,
                                        viewMode = extState.viewMode,
                                        moveTo = extState.moveTo,
                                        splitFraction = extState.splitFraction,
                                        innerSplitFraction = extState.innerSplitFraction,
                                        floatingOffset = Offset(
                                            extState.floatingOffsetX,
                                            extState.floatingOffsetY
                                        ),
                                        floatingSize = DpSize(
                                            extState.floatingWidthDp.dp,
                                            extState.floatingHeightDp.dp
                                        ),
                                        onMoveTo = {
                                            extModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetMoveTo(
                                                    it
                                                )
                                            )
                                        },
                                        onViewMode = {
                                            extModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetViewMode(
                                                    it
                                                )
                                            )
                                        },
                                        onSplitFractionChange = {
                                            extModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetSplitFraction(it)
                                            )
                                        },
                                        onInnerSplitFractionChange = {
                                            extModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetInnerSplitFraction(it)
                                            )
                                        },
                                        onFloatingBoundsChange = { offset, size ->
                                            extModel.onLayoutEvent(
                                                ToolWindowLayoutAction.SetFloatingBounds(
                                                    offset.x,
                                                    offset.y,
                                                    size.width.value,
                                                    size.height.value
                                                )
                                            )
                                        },
                                        onResize = { extModel.resize(it) },
                                        onClose = { extModel.setVisible(false) },
                                        content = {
                                            ExtensionsPanel(
                                                model = extModel,
                                                onHide = { extModel.setVisible(false) },
                                            )
                                        }
                                    )
                                )
                            }
                        }

                        val areaModifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(BlazeLayout.IslandGap)

                        ToolWindowLayoutHost(
                            mainContent = {
                                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                    navigator.lastItem.Content()
                                }
                            },
                            panels = panels,
                            modifier = areaModifier,
                        )
                    }

                    StatusBar(
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
                                expanded = shell.tasksPopupExpanded,
                                onClick = { shellModel.onEvent(MainEvent.ToggleTasksPopup) },
                            )
                            if (state.snailModeEnabled) {
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

                if (shell.tasksPopupExpanded) {
                    BackgroundTasksPopup(
                        running = running,
                        onPause = { id -> screenModel.onEvent(DownloadsEvent.Pause(id)) },
                        onCancel = { id -> screenModel.onEvent(DownloadsEvent.Cancel(id)) },
                        onOpenDetails = { id -> shellModel.onEvent(MainEvent.FocusDownload(id)) },
                        onDismiss = { shellModel.onEvent(MainEvent.DismissTasksPopup) },
                    )
                }
                if (shell.aboutVisible) {
                    AboutDialog(onDismiss = { shellModel.onEvent(MainEvent.DismissAbout) })
                }
            }
        }
    }
}

private fun List<Download>.toStatusSummary(strings: BlazeStrings): String {
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
private fun List<Download>.toStatusAlert(strings: BlazeStrings): String? {
    val attention = attention()
    val advisories = strings.downloads.advisories
    return when {
        attention.cannotResume > 0 -> advisories.alertCannotResume(attention.cannotResume)
        attention.waitingForPeers > 0 -> advisories.alertWaitingPeers(attention.waitingForPeers)
        else -> null
    }
}
