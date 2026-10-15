package org.blaze.presentation.screens.downloads

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.components.DownloadsScreenContent
import org.blaze.presentation.util.toFriendlyMessage
import org.island.ui.CrashSafeContextMenuRepresentation
import org.island.ui.notifications.LocalNotifications
import org.island.ui.notifications.NotificationType
import org.island.ui.notifications.NotificationsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

class DownloadsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<DownloadsScreenModel>()
        val state by screenModel.state.collectAsState()
        // The Details pane is its own dockable tool window (a Koin singleton, the same instance the
        // shell frames in MainScreen), so the toolbar's toggle and the "shown" projection drive that
        // model rather than the downloads list's own state.
        val detailsModel = koinScreenModel<DetailsPanelScreenModel>()
        val detailsState by detailsModel.state.collectAsState()
        // Balloons are hosted once per window (see BlazeWindow) so drag-and-drop notices and screen
        // effects share a single stack; fall back to a private queue only when hosted standalone.
        val notifications = LocalNotifications.current ?: remember { NotificationsState() }
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

        // Route every row's right-click menu through Jewel's themed popup instead of the default
        // lightweight Compose representation. The wrapper defers the popup's teardown out of the
        // click dispatch - closing it inside that dispatch crashes with "RootNodeOwner is already
        // disposed" (see ContextMenuDismissGuard).
        CompositionLocalProvider(LocalContextMenuRepresentation provides CrashSafeContextMenuRepresentation) {
            Box(Modifier.fillMaxSize()) {
                DownloadsScreenContent(
                    state = state,
                    onEvent = screenModel::onEvent,
                    detailsVisible = detailsState.visible,
                    detailsAutoHidesWhenEmpty = detailsState.viewMode.autoHidesWhenEmpty,
                    onSetDetailsVisible = detailsModel::setVisible,
                    onFetchMetadata = { url, credentials -> screenModel.fetchMetadata(url, credentials) },
                    onPrepareTorrent = { url, credentials -> screenModel.prepareTorrent(url, credentials) },
                    onInspectHls = { url, credentials ->
                        runCatching {
                            screenModel.inspectHls(
                                url,
                                credentials = credentials
                            )
                        }.getOrNull()
                    },
                    onInspectArchive = { url, credentials ->
                        runCatching {
                            screenModel.inspectArchive(
                                url,
                                credentials
                            )
                        }.getOrNull()
                    },
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
            }
        }
    }
}