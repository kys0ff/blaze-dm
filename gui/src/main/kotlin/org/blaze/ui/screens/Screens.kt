package org.blaze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.launch
import org.blaze.Di
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadRepository
import org.blaze.ui.components.AddDownloadDialog
import org.blaze.ui.components.DownloadRow
import org.blaze.ui.components.Sidebar
import org.blaze.ui.components.SidebarItem
import org.blaze.ui.components.StatusBar
import org.blaze.ui.components.ToolWindowHeader
import org.blaze.ui.components.ToolbarIconButton
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Path

class MainScreen : Screen {
    @Composable
    override fun Content() {
        // Only used for the sidebar count and the status bar summary.
        val downloads by remember { Di.downloadRepository.downloads }
            .collectAsState(initial = emptyList())

        Navigator(DownloadsScreen()) { navigator ->
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    val sidebarItems = listOf(
                        SidebarItem(
                            label = "Downloads",
                            icon = AllIconsKeys.Actions.Download,
                            id = "downloads",
                            count = downloads.size.takeIf { it > 0 }
                        ),
                        SidebarItem("Settings", AllIconsKeys.General.Settings, "settings")
                    )

                    val currentScreen = navigator.lastItem
                    val selectedItem = when (currentScreen) {
                        is DownloadsScreen -> sidebarItems[0]
                        is SettingsScreen -> sidebarItems[1]
                        else -> sidebarItems[0]
                    }

                    // The sidebar draws its own trailing border.
                    Sidebar(
                        items = sidebarItems,
                        selectedItem = selectedItem,
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

                    Box(modifier = Modifier.weight(1f)) {
                        navigator.lastItem.Content()
                    }
                }

                StatusBar(info = downloads.toStatusSummary())
            }
        }
    }
}

/** IDE-style status text, e.g. "2 downloading, 1 paused". */
fun List<Download>.toStatusSummary(): String {
    val list = this
    val parts = buildList {
        val downloading = list.count { it.state == DownloadState.DOWNLOADING }
        val queued = list.count { it.state == DownloadState.QUEUED }
        val paused = list.count { it.state == DownloadState.PAUSED }
        val failed = list.count { it.state == DownloadState.FAILED }

        if (downloading > 0) add("$downloading downloading")
        if (queued > 0) add("$queued queued")
        if (paused > 0) add("$paused paused")
        if (failed > 0) add("$failed failed")
    }

    return if (parts.isEmpty()) "Ready" else parts.joinToString(", ")
}

class DownloadsScreenModel(
    private val repository: DownloadRepository
) : ScreenModel {
    val downloads = repository.downloads

    fun addDownload(url: String) {
        if (url.isBlank()) return
        screenModelScope.launch {
            val userHome = System.getProperty("user.home")
            val defaultPath = Path.of(userHome, "Downloads").toString()
            repository.addDownload(url.trim(), defaultPath)
        }
    }

    fun pauseDownload(id: String) {
        screenModelScope.launch { repository.pauseDownload(id) }
    }

    fun resumeDownload(id: String) {
        screenModelScope.launch { repository.resumeDownload(id) }
    }

    fun removeDownload(id: String) {
        screenModelScope.launch { repository.removeDownload(id) }
    }

    fun retryDownload(id: String) {
        screenModelScope.launch { repository.retryDownload(id) }
    }

    fun pauseAll() {
        screenModelScope.launch { repository.pauseAll() }
    }

    fun resumeAll() {
        screenModelScope.launch { repository.resumeAll() }
    }

    fun clearCompleted() {
        screenModelScope.launch { repository.clearCompleted() }
    }
}

class DownloadsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val repository = remember { Di.downloadRepository }
        val screenModel = rememberScreenModel { DownloadsScreenModel(repository) }
        val downloads by screenModel.downloads.collectAsState(initial = emptyList())
        var showAddDialog by remember { mutableStateOf(false) }
        var selectedId by remember { mutableStateOf<String?>(null) }
        val listState = rememberLazyListState()

        // Toolbar actions are only enabled when there is something for them to act on.
        val hasActive = downloads.any { it.state == DownloadState.DOWNLOADING }
        val hasPaused = downloads.any { it.state == DownloadState.PAUSED }
        val hasCompleted = downloads.any { it.state == DownloadState.COMPLETED }

        if (showAddDialog) {
            AddDownloadDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { url -> screenModel.addDownload(url) }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(
                title = "Downloads",
                actions = {
                    ToolbarIconButton(
                        key = AllIconsKeys.General.Add,
                        tooltip = "Add download",
                        onClick = { showAddDialog = true }
                    )
                    ToolbarIconButton(
                        key = AllIconsKeys.Actions.Resume,
                        tooltip = "Resume all",
                        enabled = hasPaused,
                        onClick = { screenModel.resumeAll() }
                    )
                    ToolbarIconButton(
                        key = AllIconsKeys.Actions.Pause,
                        tooltip = "Pause all",
                        enabled = hasActive,
                        onClick = { screenModel.pauseAll() }
                    )
                    Divider(
                        Orientation.Vertical,
                        modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
                    )
                    ToolbarIconButton(
                        key = AllIconsKeys.Actions.GC,
                        tooltip = "Clear completed",
                        enabled = hasCompleted,
                        onClick = { screenModel.clearCompleted() }
                    )
                }
            )

            if (downloads.isEmpty()) {
                // IDE-style empty text: muted message plus an inline action link.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No downloads yet",
                            color = JewelTheme.globalColors.text.info
                        )
                        Link(text = "Add a download", onClick = { showAddDialog = true })
                    }
                }
            } else {
                // Rows draw their own hover/selection, so no dividers between them.
                VerticallyScrollableContainer(listState, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(downloads, key = { it.id }) { download ->
                            DownloadRow(
                                download = download,
                                onPause = { screenModel.pauseDownload(download.id) },
                                onResume = { screenModel.resumeDownload(download.id) },
                                onRemove = { screenModel.removeDownload(download.id) },
                                onRetry = { screenModel.retryDownload(download.id) },
                                isSelected = download.id == selectedId,
                                onSelect = { selectedId = download.id }
                            )
                        }
                    }
                }
            }
        }
    }
}

class SettingsScreen : Screen {
    @Composable
    override fun Content() {
        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(title = "Settings")

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "App configuration will appear here.",
                    color = JewelTheme.globalColors.text.info
                )
            }
        }
    }
}