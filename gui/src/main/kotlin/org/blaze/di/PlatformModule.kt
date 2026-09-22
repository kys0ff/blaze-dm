package org.blaze.di

import org.blaze.data.AppSettingsRepository
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.platform.autostart.AutoStartService
import org.blaze.platform.autostart.AutoStartServiceFactory
import org.blaze.platform.clipboard.AwtSystemClipboard
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.files.SystemFileService
import org.blaze.platform.files.SystemFileServiceFactory
import org.blaze.platform.taskbar.TaskbarProgressService
import org.blaze.platform.taskbar.TaskbarProgressServiceFactory
import org.koin.dsl.module
import java.nio.file.Path

/**
 * App-shell platform integrations: desktop settings persistence, run-at-startup
 * registration and taskbar download progress. All services degrade gracefully on
 * environments that lack the underlying capability. (The system tray lives in the
 * dedicated `:tray` module.)
 */
val platformModule = module {
    single { AppSettingsRepository(get<Path>()) }

    single<AutoStartService> { AutoStartServiceFactory.create() }

    single { AutoStartCoordinator(appSettingsRepository = get(), service = get()) }

    single<TaskbarProgressService> { TaskbarProgressServiceFactory.create() }

    single<SystemFileService> { SystemFileServiceFactory.create() }

    single<SystemClipboard> { AwtSystemClipboard() }
}
