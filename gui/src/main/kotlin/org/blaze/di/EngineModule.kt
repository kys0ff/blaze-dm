package org.blaze.di

import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.koin.dsl.module
import java.nio.file.Path

val engineModule = module {
    single {
        val userHome = System.getProperty("user.home")
        Path.of(userHome, ".blaze")
    }

    single {
        DownloadRepository(get<Path>())
    }

    single {
        EngineSettingsRepository(get<Path>())
    }

    single<DownloadEngine> {
        DownloadManager(
            scope = get(),
            repository = get(),
            settingsRepository = get()
        )
    }
}
