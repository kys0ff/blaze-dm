package org.blaze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.blaze.data.MockDownloadRepository
import org.blaze.domain.repository.DownloadRepository
import org.blaze.ui.components.AddDownloadDialog
import org.blaze.ui.components.DownloadRow
import org.blaze.ui.components.Sidebar
import org.blaze.ui.components.SidebarItem
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

class MainScreen : Screen {
    @Composable
    override fun Content() {
        Navigator(DownloadsScreen()) { navigator ->
            Row(modifier = Modifier.fillMaxSize()) {
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

                Box(modifier = Modifier.weight(1f)) {
                    navigator.lastItem.Content()
                }
            }
        }
    }
}

class DownloadsScreenModel(
    private val repository: DownloadRepository
) : ScreenModel {
    val downloads = repository.downloads

    fun addDownload(url: String) {
        screenModelScope.launch {
            repository.addDownload(url, "/home/user/Downloads")
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
    @Composable
    override fun Content() {
        // In a real app, we'd use DI (like Hilt or Koin)
        val repository = remember { MockDownloadRepository() }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Downloads",
                    style = JewelTheme.defaultTextStyle
                )

                IconButton(onClick = { showAddDialog = true }) {
                    Icon(AllIconsKeys.General.Add, contentDescription = "Add Download")
                }
            }

            if (downloads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No downloads yet")
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
                    }
                }
            }
        }
    }
}

class SettingsScreen : Screen {
    @Composable
    override fun Content() {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Settings",
                style = JewelTheme.defaultTextStyle
            )
            Text(
                text = "App configuration will appear here.",
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}