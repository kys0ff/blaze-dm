package org.blaze.di

import org.blaze.logging.LogConfigurator
import org.blaze.logging.ServerLogRepository
import org.koin.dsl.module

val loggingModule = module {
    single {
        LogConfigurator(
            settingsRepository = get(),
            storageDir = get<java.nio.file.Path>()
        )
    }

    // The in-memory log stream the Server Logs tool window renders; attaches its appender to the
    // logback root on construction, so it must be created (see Main) before the first lines are logged.
    single { ServerLogRepository() }
}
