package org.blaze.di

import org.blaze.logging.LogConfigurator
import org.koin.dsl.module

val loggingModule = module {
    single {
        LogConfigurator(
            settingsRepository = get(),
            storageDir = get<java.nio.file.Path>()
        )
    }
}
