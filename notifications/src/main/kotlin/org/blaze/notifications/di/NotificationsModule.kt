package org.blaze.notifications.di

import org.blaze.notifications.NotificationServiceFactory
import org.blaze.notifications.api.NotificationGate
import org.blaze.notifications.api.NotificationPublisher
import org.blaze.notifications.api.NotificationService
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the notifications library, mirroring the tray/platform modules:
 * the factory picks the platform backend and consumers only ever see the
 * [NotificationService] abstraction plus a [NotificationPublisher] wired to the
 * app-provided [NotificationGate] (the default gate lets everything through;
 * rebind it in the app module to add an "is the user looking?" policy).
 *
 * Usage: `modules(notificationsModule(appName = "Blaze", appIcon = "blaze"))`.
 */
fun notificationsModule(
    appName: String,
    appIcon: String = "",
): Module = module {
    single<NotificationService> { NotificationServiceFactory.create(appName, appIcon) }

    single<NotificationGate> { NotificationGate.OPEN }

    single { NotificationPublisher(service = get(), gate = get()) }
}
