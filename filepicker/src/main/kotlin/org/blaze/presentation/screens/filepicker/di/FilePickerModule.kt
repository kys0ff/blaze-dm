package org.blaze.presentation.screens.filepicker.di

import org.blaze.presentation.screens.filepicker.FilePickerScreenModel
import org.blaze.presentation.screens.filepicker.filesystem.FileSystemRepository
import org.koin.dsl.module

val filePickerModule = module {
    single { FileSystemRepository() }
    factory { FilePickerScreenModel(fileSystemRepository = get()) }
}
