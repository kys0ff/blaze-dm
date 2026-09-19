package org.blaze

import androidx.compose.ui.window.application
import org.blaze.di.appModule
import org.blaze.di.engineModule
import org.blaze.di.repositoryModule
import org.blaze.di.screenModelModule
import org.blaze.di.useCaseModule
import org.blaze.presentation.application.BlazeApplication
import org.koin.core.context.startKoin

fun main() {
    startKoin {
        modules(
            appModule,
            engineModule,
            repositoryModule,
            useCaseModule,
            screenModelModule,
        )
    }

    application {
        BlazeApplication()
    }
}