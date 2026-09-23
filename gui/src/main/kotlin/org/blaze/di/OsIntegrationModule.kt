package org.blaze.di

import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.koin.dsl.module

/**
 * The OS-integration singletons the launcher and the UI share: the argument parser (also used by the
 * macOS event bridge and the IPC receiver, so validation happens exactly one way) and the inbox that
 * carries external-open requests from before the window exists to the download workflow after it does.
 */
val osIntegrationModule = module {
    single { ExternalOpenParser() }
    single { ExternalOpenInbox() }
}
