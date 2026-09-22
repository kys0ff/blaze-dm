package org.blaze.presentation.screens.downloads

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.blaze.presentation.components.notifications.NotificationHost
import org.blaze.presentation.components.notifications.NotificationsState
import org.blaze.presentation.screens.downloads.components.DownloadsScreenContent
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.ContextMenuRepresentation

class DownloadsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        val notifications = remember { NotificationsState() }

        LaunchedEffect(Unit) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    is DownloadsEffect.ShowError -> notifications.error(effect.message)
                    is DownloadsEffect.ShowMessage -> notifications.info(effect.message)
                }
            }
        }

        val settings by screenModel.settingsRepository.settings.collectAsState()

        // Route every row's right-click menu through Jewel's themed popup instead of the
        // default lightweight Compose representation.
        CompositionLocalProvider(LocalContextMenuRepresentation provides ContextMenuRepresentation) {
            Box(Modifier.fillMaxSize()) {
                DownloadsScreenContent(
                    state = state,
                    onEvent = screenModel::onEvent,
                    onFetchMetadata = { url -> screenModel.fetchMetadata(url) },
                    onResolveDestinationPath = { url, savePath, name -> screenModel.resolveDestinationPath(url, savePath, name) },
                    onDetectPortable = { path -> screenModel.detectPortable(path) },
                    onImportPortable = { artifact, dest -> screenModel.importPortable(artifact, dest) },
                    onNotify = { message, isError ->
                        if (isError) notifications.error(message) else notifications.info(message)
                    },
                    fileConflictBehavior = settings.fileConflictBehavior,
                )

                NotificationHost(
                    state = notifications,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}