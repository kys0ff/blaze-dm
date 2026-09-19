package org.blaze.presentation.screens.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.blaze.presentation.screens.downloads.components.DownloadsScreenContent
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

class DownloadsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()

        LaunchedEffect(Unit) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    is DownloadsEffect.ShowError -> println("Error: ${effect.message}")
                    is DownloadsEffect.ShowMessage -> println("Message: ${effect.message}")
                }
            }
        }

        val settings by screenModel.settingsRepository.settings.collectAsState()

        DownloadsScreenContent(
            state = state,
            onEvent = screenModel::onEvent,
            onFetchMetadata = { url -> screenModel.fetchMetadata(url) },
            onResolveDestinationPath = { url, savePath, name -> screenModel.resolveDestinationPath(url, savePath, name) },
            fileConflictBehavior = settings.fileConflictBehavior,
        )
    }
}
