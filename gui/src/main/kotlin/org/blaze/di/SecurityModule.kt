package org.blaze.di

import org.blaze.security.ConsentService
import org.blaze.service.security.ClientConsentGate
import org.koin.dsl.module

/**
 * The consent layer: one singleton that both suspends the local-API transport on unknown clients
 * and gates extension jars, and whose pending-request queue the app-wide consent dialog renders.
 */
val securityModule = module {
    single { ConsentService(appSettingsRepository = get(), scope = get()) }

    // The transport only knows the port, not the app class that implements it.
    single<ClientConsentGate> { get<ConsentService>() }
}
