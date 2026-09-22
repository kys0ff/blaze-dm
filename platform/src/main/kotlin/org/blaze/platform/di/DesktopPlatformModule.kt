package org.blaze.platform.di

import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.autostart.AutoStartController
import org.blaze.platform.autostart.AutoStartService
import org.blaze.platform.autostart.AutoStartServiceFactory
import org.blaze.platform.clipboard.AwtSystemClipboard
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.files.SystemFileService
import org.blaze.platform.files.SystemFileServiceFactory
import org.blaze.platform.taskbar.LauncherEntryInstaller
import org.blaze.platform.taskbar.TaskbarProgressService
import org.blaze.platform.taskbar.TaskbarProgressServiceFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the whole platform library, parameterized by the host app's
 * [identity]. Consumers add it to `startKoin` and only ever inject the service
 * abstractions; the factories pick the per-OS backends with graceful fallbacks.
 *
 * App-specific glue (settings-backed coordinators, icon assets) intentionally stays
 * outside this module - bind it in the app's own Koin module.
 */
fun desktopPlatformModule(identity: PlatformIdentity): Module = module {
    single { identity }

    single { LauncherEntryInstaller(identity) }

    single<AutoStartService> { AutoStartServiceFactory.create(identity) }
    single { AutoStartController(get()) }

    single<TaskbarProgressService> { TaskbarProgressServiceFactory.create(identity) }

    single<SystemFileService> { SystemFileServiceFactory.create() }

    single<SystemClipboard> { AwtSystemClipboard() }
}
