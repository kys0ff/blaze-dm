package org.blaze.di

import org.blaze.presentation.screens.downloads.DownloadsScreenModel
import org.blaze.presentation.screens.settings.SettingsScreenModel
import org.koin.dsl.module

val screenModelModule = module {
    factory {
        DownloadsScreenModel(
            getDownloads = get(),
            fetchMetadataUseCase = get(),
            addDownload = get(),
            getDestinationPath = get(),
            settingsRepository = get(),
            pauseDownload = get(),
            resumeDownload = get(),
            cancelDownload = get(),
            removeDownload = get(),
            retryDownload = get(),
            pauseAllDownloads = get(),
            resumeAllDownloads = get(),
            clearCompletedDownloads = get(),
            detectPortableDownload = get(),
            importPortableDownload = get(),
            systemFileService = get(),
            clipboard = get()
        )
    }

    factory {
        SettingsScreenModel(
            settingsRepository = get(),
            appSettingsRepository = get(),
            autoStartCoordinator = get(),
            trayService = get(),
            resolverRegistry = get(),
            resolverSettingsRepository = get(),
            themeRegistry = get(),
            themeSettingsRepository = get()
        )
    }
}
