package org.blaze.di

import org.blaze.i18n.LocaleManager
import org.blaze.i18n.getStrings
import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.blaze.presentation.screens.extensions.ExtensionsScreenModel
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import org.blaze.presentation.screens.settings.SettingsScreenModel
import org.blaze.presentation.screens.settings.SettingsUseCases
import org.koin.dsl.module

val screenModelModule = module {
    factory {
        DownloadsScreenModel(
            getDownloads = get(),
            fetchMetadataUseCase = get(),
            prepareTorrentUseCase = get(),
            addDownload = get(),
            getDestinationPath = get(),
            settingsRepository = get(),
            appSettingsRepository = get(),
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

    factory {
        ExtensionsScreenModel(
            registry = get(),
            settingsRepository = get(),
            consentService = get(),
        )
    }

    factory {
        ServerLogWindowModel(appSettingsRepository = get())
    }
}
