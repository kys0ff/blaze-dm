package org.blaze.di

import org.blaze.data.DownloadRepositoryImpl
import org.blaze.data.SettingsBackedFilePickerLastDirectoryStore
import org.blaze.domain.repository.DownloadRepository
import org.blaze.presentation.screens.filepicker.memory.FilePickerLastDirectoryStore
import org.koin.dsl.module

val repositoryModule = module {
    single<DownloadRepository> {
        DownloadRepositoryImpl(
            api = get(),
            scope = get()
        )
    }

    /**
     * The file pickers' memory: one remembered directory per context, persisted in `app.json` so the
     * download folder picker and the extension jar picker each reopen where they were last used.
     */
    single<FilePickerLastDirectoryStore> {
        SettingsBackedFilePickerLastDirectoryStore(
            appSettingsRepository = get(),
            scope = get(),
        )
    }
}
