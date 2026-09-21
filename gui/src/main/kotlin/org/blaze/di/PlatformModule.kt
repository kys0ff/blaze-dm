package org.blaze.di

import org.blaze.data.AppSettingsRepository
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.platform.autostart.AutoStartService
import org.blaze.platform.autostart.AutoStartServiceFactory
import org.koin.dsl.module
import java.nio.file.Path

/**
 * App-shell platform integrations: desktop settings persistence and run-at-startup
 * registration. All services degrade gracefully on environments that lack the
 * underlying capability. (The system tray lives in the dedicated `:tray` module.)
 */
val platformModule = module {
    single { AppSettingsRepository(get<Path>()) }

    single<AutoStartService> { AutoStartServiceFactory.create() }

    single { AutoStartCoordinator(appSettingsRepository = get(), service = get()) }
}
