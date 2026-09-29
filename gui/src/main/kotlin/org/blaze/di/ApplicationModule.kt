package org.blaze.di

import org.blaze.download.api.DownloadsApi
import org.blaze.service.ApplicationDownloadService
import org.blaze.service.security.PathValidator
import org.koin.dsl.module

/**
 * Wires the single application-level download API every GUI collaborator consumes.
 *
 * The GUI owns its engine and runs fully in-process, so this instance uses [PathValidator.TRUSTED]
 * (the user picked the destination folder directly - no confinement needed) and no transport auth: there
 * is no network hop to protect. When the app also exposes the loopback HTTP API (see `Main`), that
 * transport layers its own confining validator and token on top of the *same* engine; the in-process
 * instance the UI talks to stays unrestricted and instant.
 */
val applicationModule = module {
    single<DownloadsApi> {
        ApplicationDownloadService(
            engine = get(),
            scope = get(),
            pathValidator = PathValidator.TRUSTED,
            settingsRepository = get(),
        )
    }
}
