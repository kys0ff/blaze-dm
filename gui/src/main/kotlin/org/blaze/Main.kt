package org.blaze

import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import org.blaze.di.appModule
import org.blaze.di.engineModule
import org.blaze.di.loggingModule
import org.blaze.di.platformModule
import org.blaze.di.repositoryModule
import org.blaze.di.resolverModule
import org.blaze.di.screenModelModule
import org.blaze.di.themeModule
import org.blaze.di.useCaseModule
import org.blaze.i18n.i18nModule
import org.blaze.logging.LogConfigurator
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.presentation.application.BlazeApplication
import org.blaze.presentation.screens.filepicker.di.filePickerModule
import org.blaze.tray.di.trayModule
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory

fun main() {
    val koin = startKoin {
        modules(
            appModule,
            engineModule,
            loggingModule,
            platformModule,
            repositoryModule,
            resolverModule,
            themeModule,
            useCaseModule,
            screenModelModule,
            i18nModule,
            filePickerModule,
            trayModule,
        )
    }.koin

    // Apply persisted logging settings before anything else logs, then keep them in sync.
    koin.get<LogConfigurator>().start(koin.get<CoroutineScope>())
    LoggerFactory.getLogger("org.blaze.Main").info("Blaze is starting up.")

    // Reconcile the run-at-startup OS registration with the persisted setting (repairs drift).
    koin.get<AutoStartCoordinator>().sync()

    application {
        BlazeApplication()
    }
}