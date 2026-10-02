package org.blaze.di

import kotlinx.coroutines.CoroutineScope
import org.blaze.server.DownloadServiceController
import org.blaze.service.LocalApiLauncher
import org.koin.dsl.module
import java.nio.file.Path

/**
 * The embedded local download API server and the GUI's handle on it. The [LocalApiLauncher] is a single
 * so `Main`'s startup, the shutdown hook and the Server Logs tool window all drive the *same* server
 * instance rather than racing to bind a second one.
 */
val serviceModule = module {
    single { LocalApiLauncher(storageDir = get<Path>(), scope = get<CoroutineScope>()) }

    single {
        DownloadServiceController(
            launcher = get(),
            api = get(),
            storageDir = get<Path>(),
            consentGate = get(),
        )
    }
}
