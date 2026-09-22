package org.blaze.di

import org.blaze.data.AppSettingsRepository
import org.blaze.platform.autostart.AutoStartCoordinator
import org.koin.dsl.module
import java.nio.file.Path

/**
 * Blaze-specific app-shell bindings on top of the reusable `:platform` services
 * (wired via [org.blaze.platform.di.desktopPlatformModule]): desktop settings
 * persistence and the run-at-startup coordinator bridging the persisted setting to
 * the OS. (The system tray lives in the dedicated `:tray` module.)
 */
val platformModule = module {
    single { AppSettingsRepository(get<Path>()) }

    single { AutoStartCoordinator(appSettingsRepository = get(), service = get()) }
}
