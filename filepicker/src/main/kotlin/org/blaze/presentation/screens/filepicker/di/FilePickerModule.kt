package org.blaze.presentation.screens.filepicker.di

import org.blaze.presentation.screens.filepicker.FilePickerScreenModel
import org.blaze.presentation.screens.filepicker.filesystem.FileSystemRepository
import org.blaze.presentation.screens.filepicker.memory.FilePickerLastDirectoryStore
import org.koin.dsl.module

/**
 * The picker's own bindings. The host is expected to provide a [FilePickerLastDirectoryStore] -
 * a real app binds one that survives a launch, so each picker reopens where the user last worked.
 */
val filePickerModule = module {
    single { FileSystemRepository() }
    factory {
        FilePickerScreenModel(
            fileSystemRepository = get(),
            lastDirectoryStore = get<FilePickerLastDirectoryStore>(),
        )
    }
}
