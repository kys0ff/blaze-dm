package org.blaze.di

import org.blaze.i18n.LocaleManager
import org.blaze.i18n.getStrings
import org.blaze.presentation.components.panel.DetailsLayoutStore
import org.blaze.presentation.screens.downloads.DetailsPanelScreenModel
import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.blaze.presentation.screens.extensions.ExtensionsWindowModel
import org.blaze.presentation.screens.main.MainScreenModel
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import org.blaze.presentation.screens.settings.SettingsScreenModel
import org.blaze.presentation.screens.settings.SettingsUseCases
import org.blaze.server.DownloadServiceController
import org.koin.dsl.module

val screenModelModule = module {
    single {
        // The application shell owns only the window-level, ephemeral UI (About dialog, background-tasks
        // popup, stripe selection, running projection) and folds the shell's app-wide commands into it.
        // It reads the dockable windows it toggles (Server Logs, Extensions) - both singletons so the
        // shell and the view drive the very same instances - and projects the live downloads stream.
        MainScreenModel(
            commandBus = get(),
            getDownloads = get(),
            serverLogWindowModel = get(),
            extensionsWindowModel = get(),
        )
    }

    single {
        DownloadsScreenModel(
            getDownloads = get(),
            fetchMetadataUseCase = get(),
            prepareTorrentUseCase = get(),
            addDownload = get(),
            getDestinationPath = get(),
            inspectHlsUseCase = get(),
            inspectArchiveUseCase = get(),
            addDownloadWithSourceUseCase = get(),
            settingsRepository = get(),
            appSettingsRepository = get(),
            detailsPanelModel = get(),
            pauseDownload = get(),
            resumeDownload = get(),
            cancelDownload = get(),
            removeDownload = get(),
            retryDownload = get(),
            setPriority = get(),
            pauseAllDownloads = get(),
            resumeAllDownloads = get(),
            clearCompletedDownloads = get(),
            detectPortableDownload = get(),
            importPortableDownload = get(),
            systemFileService = get(),
            clipboard = get(),
            externalOpenInbox = get(),
            commandBus = get()
        )
    }

    factory {
        SettingsUseCases(
            settingsRepository = get(),
            appSettingsRepository = get(),
            autoStartCoordinator = get(),
            trayService = get(),
            notificationService = get(),
            resolverRegistry = get(),
            resolverSettingsRepository = get(),
            themeRegistry = get(),
            themeSettingsRepository = get(),
            consentService = get(),
        )
    }

    factory {
        SettingsScreenModel(
            useCases = get(),
            // The install-refusal copy is resolved once per dialog from the current locale; Settings
            // is a short-lived modal, so a mid-dialog language flip leaving older copy is acceptable.
            strings = getStrings(get<LocaleManager>().currentLocale.value),
        )
    }

    single {
        // A dockable tool window outlives any single composition of its pane, so it is a singleton like
        // the Server Logs window: the stripe raises and lowers it without losing its live rows or dock.
        ExtensionsWindowModel(
            appSettingsRepository = get(),
            registry = get(),
            settingsRepository = get(),
            consentService = get(),
        )
    }

    single {
        // The Server Logs window model owns its pane's collaborators so the composable resolves none: the
        // shared log repository supplies the live stream, the controller's status flow feeds the header,
        // and lifecycle toggles go out on the command bus - the shell still owns the launcher itself.
        ServerLogWindowModel(
            appSettingsRepository = get(),
            logRepository = get(),
            serverStatusFlow = get<DownloadServiceController>().status,
            commandBus = get(),
        )
    }

    single {
        // The Details tool window is a dockable pane like the two above: a singleton so the shell
        // (MainScreen), the downloads screen and the download model's "first selection re-opens it"
        // path all drive the very same instance. Its layout persists through the Details store, which
        // also carries the pane-only group-tabs flag.
        DetailsPanelScreenModel(DetailsLayoutStore(get()))
    }
}
