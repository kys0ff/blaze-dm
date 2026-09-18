package org.blaze.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.blaze.domain.repository.DownloadRepository
import org.blaze.ui.components.AddDownloadDialog
import org.blaze.ui.components.DownloadRow
import org.blaze.ui.components.Sidebar
import org.blaze.ui.components.SidebarItem
import org.blaze.ui.components.StatusBar
import org.blaze.ui.components.ToolWindowHeader
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Path

class MainScreen : Screen {
    @Composable
    override fun Content() {
        Navigator(DownloadsScreen()) { navigator ->
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    val sidebarItems = listOf(
                        SidebarItem("Downloads", AllIconsKeys.Actions.Download, "downloads"),
                        SidebarItem("Settings", AllIconsKeys.General.Settings, "settings")
                    )

                    val currentScreen = navigator.lastItem
                    val selectedItem = when (currentScreen) {
                        is DownloadsScreen -> sidebarItems[0]
                        is SettingsScreen -> sidebarItems[1]
                        else -> sidebarItems[0]
                    }

                    Sidebar(
                        items = sidebarItems,
                        selectedItem = selectedItem,
                        onItemSelected = { item ->
                            when (item.id) {
                                "downloads" -> if (currentScreen !is DownloadsScreen) navigator.replaceAll(DownloadsScreen())
                                "settings" -> if (currentScreen !is SettingsScreen) navigator.replaceAll(SettingsScreen())
                            }
                        }
                    )

                    Divider(Orientation.Vertical)

                    Box(modifier = Modifier.weight(1f)) {
                        navigator.lastItem.Content()
                    }
                }
                
                StatusBar(info = "Ready")
            }
        }
    }
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
}

class DownloadsScreen : Screen {
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val repository = remember { org.blaze.Di.downloadRepository }
        val screenModel = rememberScreenModel { DownloadsScreenModel(repository) }
        val downloads by screenModel.downloads.collectAsState(initial = emptyList())
        var showAddDialog by remember { mutableStateOf(false) }

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
                    ActionButton(onClick = { showAddDialog = true }, tooltip = { Text("Add Download") }) {
                        Icon(AllIconsKeys.General.Add, null, modifier = Modifier.size(16.dp))
                    }
                    ActionButton(onClick = { /* TODO */ }, tooltip = { Text("Resume All") }) {
                        Icon(AllIconsKeys.Actions.Resume, null, modifier = Modifier.size(16.dp))
                    }
                    ActionButton(onClick = { /* TODO */ }, tooltip = { Text("Pause All") }) {
                        Icon(AllIconsKeys.Actions.Pause, null, modifier = Modifier.size(16.dp))
                    }
                    Divider(Orientation.Vertical, modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
                    ActionButton(onClick = { /* TODO */ }, tooltip = { Text("Clear Completed") }) {
                        Icon(AllIconsKeys.Actions.GC, null, modifier = Modifier.size(16.dp))
                    }
                }
            )

            if (downloads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AllIconsKeys.General.Balloon, null, modifier = Modifier.size(64.dp), tint = JewelTheme.globalColors.text.disabled)
                        Text("No downloads yet", color = JewelTheme.globalColors.text.disabled)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(downloads, key = { it.id }) { download ->
                        DownloadRow(
                            download = download,
                            onPause = { screenModel.pauseDownload(download.id) },
                            onResume = { screenModel.resumeDownload(download.id) },
                            onRemove = { screenModel.removeDownload(download.id) },
                            onRetry = { screenModel.retryDownload(download.id) }
                        )
                        Divider(Orientation.Horizontal)
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
                    style = JewelTheme.defaultTextStyle
                )
            }
        }
    }
}