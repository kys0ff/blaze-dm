package org.blaze.presentation.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.components.Sidebar
import org.blaze.presentation.components.SidebarItem
import org.blaze.presentation.components.StatusBar
import org.blaze.presentation.screens.settings.SettingsScreen
import org.blaze.presentation.screens.downloads.DownloadsScreen
import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.jetbrains.jewel.ui.icons.AllIconsKeys

class MainScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        val downloads = state.downloads

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
