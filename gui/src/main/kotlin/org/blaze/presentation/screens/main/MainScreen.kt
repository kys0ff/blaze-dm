package org.blaze.presentation.screens.main

import androidx.compose.foundation.layout.*
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
import org.blaze.presentation.components.AppMetrics
import org.blaze.presentation.components.BackgroundTasksPopup
import org.blaze.presentation.components.BackgroundTasksStatusItem
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.panel.ToolWindowScreenModel
import org.blaze.presentation.screens.downloads.*
import org.blaze.presentation.screens.downloads.components.DownloadDetailsPanel
import org.blaze.presentation.screens.extensions.ExtensionsPanel
import org.blaze.presentation.screens.extensions.ExtensionsWindowModel
import org.blaze.presentation.screens.serverlog.ServerLogPanel
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import org.blaze.presentation.search.SearchController
import org.blaze.presentation.util.formatSpeed
import org.island.theme.ui.IslandLayout
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTypography
import org.island.ui.StatusBar
import org.island.ui.toolwindow.*
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.koin.compose.koinInject

/**
 * The application shell. It owns only the window-level, ephemeral UI - the About dialog, the
 * background-tasks popup, the stripe's selected tool window and the running transfers - and folds the
 * shell's app-wide commands into them. Everything else stays with the model that owns it: the downloads
 * list with [DownloadsScreenModel], the Details/Server Logs/Extensions panes with their own window
 * models, so the shell never duplicates their state or resolves their collaborators.
 *
 * The body reads as a small orchestrator: collect the models, frame the [Navigator] content, and hand
 * the repetitive layout to focused sub-composables ([MainToolWindowStripe], [MainStatusBar],
 * [MainOverlayDialogs]) plus the [toolWindowSpec] builder that turns any dockable [ToolWindowScreenModel]
 * into a [ToolWindowSpec] the [ToolWindowLayoutHost] can dock.
 */
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

        // Each dockable pane frames itself through its own model; the shell only reads the layout to place it.
        val logModel = koinScreenModel<ServerLogWindowModel>()
        val logLayout by logModel.layout.collectAsState()

        val extModel = koinScreenModel<ExtensionsWindowModel>()
        val extLayout by extModel.layout.collectAsState()

        val detailsModel = koinScreenModel<DetailsPanelScreenModel>()
        val detailsState by detailsModel.state.collectAsState()
        val detailsLayout by detailsModel.layout.collectAsState()

        val running = shell.running

        Navigator(DownloadsScreen()) { navigator ->
            LaunchedEffect(shellModel, searchController) {
                shellModel.effects.collect { effect ->
                    when (effect) {
                        is MainEffect.JumpToDownload -> searchController.activateDownload(effect.id)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        val currentScreen = navigator.lastItem

                        MainToolWindowStripe(
                            strings = strings,
                            downloadCount = downloads.size,
                            selectedId = shell.activeToolWindow.stripeId,
                            onSelected = { shellModel.onEvent(MainEvent.SelectToolWindow(it)) },
                        )

                        val detailsDownload: Download? = state.selectedIds
                            .takeIf { it.size == 1 }
                            ?.first()
                            ?.let { selected -> downloads.find { it.id == selected } }
                        val detailsShown = detailsLayout.visible &&
                            (detailsDownload != null || !detailsLayout.viewMode.autoHidesWhenEmpty)

                        val panels = buildToolWindowPanels(
                            strings = strings,
                            log = logModel to logLayout,
                            details = DetailsPane(
                                model = detailsModel,
                                layout = detailsLayout,
                                groupTabs = detailsState.groupTabs,
                                download = detailsDownload,
                                show = currentScreen is DownloadsScreen && detailsShown,
                            ),
                            extensions = extModel to extLayout,
                        )

                        ToolWindowLayoutHost(
                            mainContent = {
                                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                    navigator.lastItem.Content()
                                }
                            },
                            panels = panels,
                            paneMinWidth = AppMetrics.DetailsPaneMinWidth,
                            contentMinWidth = AppMetrics.ListPaneMinWidth,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(IslandLayout.IslandGap),
                        )
                    }

                    MainStatusBar(
                        state = state,
                        strings = strings,
                        runningCount = running.size,
                        tasksPopupExpanded = shell.tasksPopupExpanded,
                        onToggleTasksPopup = { shellModel.onEvent(MainEvent.ToggleTasksPopup) },
                    )
                }

                MainOverlayDialogs(
                    shell = shell,
                    running = running,
                    onPause = { id -> screenModel.onEvent(DownloadsEvent.Pause(id)) },
                    onCancel = { id -> screenModel.onEvent(DownloadsEvent.Cancel(id)) },
                    onOpenDetails = { id -> shellModel.onEvent(MainEvent.FocusDownload(id)) },
                    onDismissTasks = { shellModel.onEvent(MainEvent.DismissTasksPopup) },
                    onDismissAbout = { shellModel.onEvent(MainEvent.DismissAbout) },
                )
            }
        }
    }
}

// ── dockable tool windows ───────────────────────────────────────────────────────

/** The Details pane's inputs to [buildToolWindowPanels]: its layout plus the selection it shows. */
private class DetailsPane(
    val model: DetailsPanelScreenModel,
    val layout: ToolWindowLayout,
    val groupTabs: Boolean,
    val download: Download?,
    val show: Boolean,
)

/**
 * Assembles the visible dockable panes - Server Logs, Details, Extensions - into the specs the host
 * docks. Each pane's layout is folded into a [ToolWindowSpec] through [toolWindowSpec], so the shell
 * wires all three identically and only their content and title differ.
 */
private fun buildToolWindowPanels(
    strings: BlazeStrings,
    log: Pair<ServerLogWindowModel, ToolWindowLayout>,
    details: DetailsPane,
    extensions: Pair<ExtensionsWindowModel, ToolWindowLayout>,
): List<ToolWindowSpec> {
    val (logModel, logLayout) = log
    val (extModel, extLayout) = extensions
    val detailsModel = details.model

    return buildList {
        if (logLayout.visible) {
            add(
                toolWindowSpec(
                    model = logModel,
                    layout = logLayout,
                    id = "server_log",
                    title = strings.serverLog.title,
                    onResize = logModel::resize,
                    content = {
                        ServerLogPanel(
                            model = logModel,
                            onHide = { logModel.setVisible(false) },
                        )
                    },
                ),
            )
        }

        if (details.show) {
            add(
                toolWindowSpec(
                    model = detailsModel,
                    layout = details.layout,
                    id = "download_details",
                    title = details.download?.name ?: strings.downloads.dialogs.details.title,
                    onResize = detailsModel::resize,
                    content = {
                        DownloadDetailsPanel(
                            download = details.download,
                            onHide = { detailsModel.setVisible(false) },
                            modifier = Modifier.fillMaxSize(),
                            viewMode = details.layout.viewMode,
                            moveTo = details.layout.moveTo,
                            groupTabs = details.groupTabs,
                            onViewMode = {
                                detailsModel.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(it))
                            },
                            onMoveTo = {
                                detailsModel.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(it))
                            },
                            onResize = detailsModel::resize,
                            onToggleGroupTabs = { detailsModel.setGroupTabs(it) },
                        )
                    },
                ),
            )
        }

        if (extLayout.visible) {
            add(
                toolWindowSpec(
                    model = extModel,
                    layout = extLayout,
                    id = "extensions",
                    title = strings.extensions.title,
                    onResize = extModel::resize,
                    content = {
                        ExtensionsPanel(
                            model = extModel,
                            onHide = { extModel.setVisible(false) },
                        )
                    },
                ),
            )
        }
    }
}

/**
 * Frames any dockable [model] as a [ToolWindowSpec] from its current [layout]. Every layout callback
 * funnels through [ToolWindowScreenModel.onLayoutEvent] with the shared [ToolWindowLayoutAction]s, so the
 * three panes are wired identically and none drifts from the reducer's rules; only the [content],
 * [title] and pane-specific [onResize] preset differ.
 */
private fun toolWindowSpec(
    model: ToolWindowScreenModel,
    layout: ToolWindowLayout,
    id: String,
    title: String,
    onResize: (ToolWindowResize) -> Unit,
    content: @Composable () -> Unit,
): ToolWindowSpec = ToolWindowSpec(
    id = id,
    title = title,
    visible = true,
    viewMode = layout.viewMode,
    moveTo = layout.moveTo,
    splitFraction = layout.splitFraction,
    innerSplitFraction = layout.innerSplitFraction,
    floatingOffset = Offset(layout.floatingOffsetX, layout.floatingOffsetY),
    floatingSize = DpSize(layout.floatingWidthDp.dp, layout.floatingHeightDp.dp),
    onMoveTo = { model.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(it)) },
    onViewMode = { model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(it)) },
    onSplitFractionChange = { model.onLayoutEvent(ToolWindowLayoutAction.SetSplitFraction(it)) },
    onInnerSplitFractionChange = { model.onLayoutEvent(ToolWindowLayoutAction.SetInnerSplitFraction(it)) },
    onFloatingBoundsChange = { offset, size ->
        model.onLayoutEvent(
            ToolWindowLayoutAction.SetFloatingBounds(
                offset.x,
                offset.y,
                size.width.value,
                size.height.value,
            ),
        )
    },
    onResize = onResize,
    onClose = { model.setVisible(false) },
    content = content,
)

// ── shell chrome ────────────────────────────────────────────────────────────────

/** The left tool-window stripe: the two side-bar destinations, with the live download count on the list. */
@Composable
private fun MainToolWindowStripe(
    strings: BlazeStrings,
    downloadCount: Int,
    selectedId: String,
    onSelected: (MainToolWindowId) -> Unit,
) {
    ToolWindowStripe(
        items = listOf(
            SidebarItem(
                label = strings.downloads.title,
                icon = AllIconsKeys.Actions.Download,
                id = MainToolWindowId.DOWNLOADS.stripeId,
                count = downloadCount.takeIf { it > 0 },
            ),
            SidebarItem(
                label = strings.extensions.title,
                icon = AllIconsKeys.Nodes.Plugin,
                id = MainToolWindowId.EXTENSIONS.stripeId,
            ),
        ),
        selectedId = selectedId,
        onItemSelected = { item ->
            when (item.id) {
                MainToolWindowId.DOWNLOADS.stripeId -> onSelected(MainToolWindowId.DOWNLOADS)
                MainToolWindowId.EXTENSIONS.stripeId -> onSelected(MainToolWindowId.EXTENSIONS)
            }
        },
    )
}

/** The bottom status bar: the running-summary text, aggregate throughput, a risk alert and the trailing chips. */
@Composable
private fun MainStatusBar(
    state: DownloadsState,
    strings: BlazeStrings,
    runningCount: Int,
    tasksPopupExpanded: Boolean,
    onToggleTasksPopup: () -> Unit,
) {
    val downloads = state.downloads
    StatusBar(
        info = if (state.selectedIds.size > 1) {
            strings.downloads.selection.count(state.selectedIds.size)
        } else {
            downloads.toStatusSummary(strings)
        },
        throughput = downloads.aggregateSpeed().takeIf { it > 0 }?.let { formatSpeed(it, strings) },
        alert = downloads.toStatusAlert(strings),
        trailing = {
            BackgroundTasksStatusItem(
                runningCount = runningCount,
                expanded = tasksPopupExpanded,
                onClick = onToggleTasksPopup,
            )
            if (state.snailModeEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        key = BlazeIcons.SnailMode,
                        contentDescription = null,
                        tint = IslandSemanticColors.warning,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = strings.statusSummary.snailMode("${state.snailSpeedKbps} KB/s"),
                        style = IslandTypography.statusBar,
                        color = IslandSemanticColors.warning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

/** The overlays drawn above the frame: the background-tasks popup and the About dialog, each gated by shell state. */
@Composable
private fun MainOverlayDialogs(
    shell: MainState,
    running: List<Download>,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onDismissTasks: () -> Unit,
    onDismissAbout: () -> Unit,
) {
    if (shell.tasksPopupExpanded) {
        BackgroundTasksPopup(
            running = running,
            onPause = onPause,
            onCancel = onCancel,
            onOpenDetails = onOpenDetails,
            onDismiss = onDismissTasks,
        )
    }
    if (shell.aboutVisible) {
        AboutDialog(onDismiss = onDismissAbout)
    }
}

// ── status-bar projections ──────────────────────────────────────────────────────

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
