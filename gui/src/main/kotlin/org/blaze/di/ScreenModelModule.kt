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
            pauseDownload = get(),
            resumeDownload = get(),
            cancelDownload = get(),
            removeDownload = get(),
            retryDownload = get(),
            pauseAllDownloads = get(),
            resumeAllDownloads = get(),
            clearCompletedDownloads = get()
        )
    }

    factory {
        SettingsScreenModel(
            settingsRepository = get()
        )
    }
}
