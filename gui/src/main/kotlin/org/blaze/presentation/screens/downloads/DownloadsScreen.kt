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
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.notifications.NotificationHost
import org.blaze.presentation.components.notifications.NotificationType
import org.blaze.presentation.components.notifications.NotificationsState
import org.blaze.presentation.screens.downloads.components.DownloadsScreenContent
import org.blaze.presentation.util.toFriendlyMessage
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.ContextMenuRepresentation

class DownloadsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        val notifications = remember { NotificationsState() }
        val strings = blazeStrings
        val advisories = strings.downloads.advisories

        LaunchedEffect(strings) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    is DownloadsEffect.ShowError -> notifications.error(effect.message)
                    is DownloadsEffect.ShowMessage -> notifications.info(effect.message)

                    // Lifecycle notices arrive as data so their wording can be localized here.
                    is DownloadsEffect.DownloadCompleted ->
                        notifications.info(advisories.completed(effect.name), advisories.titleCompleted)

                    is DownloadsEffect.DownloadFailed -> notifications.error(
                        advisories.failed(
                            effect.name,
                            effect.reason?.toFriendlyMessage(strings) ?: strings.common.unknown
                        ),
                        advisories.titleFailed
                    )

                    is DownloadsEffect.DownloadRestarted -> notifications.warning(
                        advisories.restarted(effect.name, effect.lostPercent),
                        advisories.titleRestarted
                    )
                }
            }
        }

        val settings by screenModel.settingsRepository.settings.collectAsState()
        val externalRequests by screenModel.externalRequests.collectAsState()

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
                    onNotify = { message, type ->
                        when (type) {
                            NotificationType.INFO -> notifications.info(message)
                            NotificationType.WARNING -> notifications.warning(message)
                            NotificationType.ERROR -> notifications.error(message)
                        }
                    },
                    fileConflictBehavior = settings.fileConflictBehavior,
                    externalRequests = externalRequests,
                    onExternalShown = screenModel::externalHandled,
                )

                NotificationHost(
                    state = notifications,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}