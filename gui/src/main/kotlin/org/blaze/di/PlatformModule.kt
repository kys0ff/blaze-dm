package org.blaze.di

import org.blaze.data.AppSettingsRepository
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.platform.autostart.AutoStartService
import org.blaze.platform.autostart.AutoStartServiceFactory
import org.blaze.platform.tray.TrayService
import org.blaze.platform.tray.TrayServiceFactory
import org.koin.dsl.module
import java.nio.file.Path

/**
 * App-shell platform integrations: desktop settings persistence, system tray,
 * run-at-startup registration. All services degrade gracefully on environments
 * that lack the underlying capability.
 */
val platformModule = module {
    single { AppSettingsRepository(get<Path>()) }

    single<TrayService> { TrayServiceFactory.create() }

    single<AutoStartService> { AutoStartServiceFactory.create() }

    single { AutoStartCoordinator(appSettingsRepository = get(), service = get()) }
}
