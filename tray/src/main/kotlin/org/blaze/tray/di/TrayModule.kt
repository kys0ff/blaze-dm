package org.blaze.tray.di

import org.blaze.tray.TrayServiceFactory
import org.blaze.tray.api.TrayService
import org.koin.dsl.module

/**
 * Koin bindings for the tray module. The factory picks the platform backend
 * (StatusNotifierItem/D-Bus on Linux, AWT elsewhere); consumers only ever see
 * the [TrayService] abstraction.
 */
val trayModule = module {
    single<TrayService> { TrayServiceFactory.create() }
}
