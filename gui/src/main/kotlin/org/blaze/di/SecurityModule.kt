package org.blaze.di

import org.blaze.resolver.core.PluginLoader
import org.blaze.security.ConsentService
import org.blaze.security.ConsentSubjectKind
import org.blaze.security.ExtensionDescriptor
import org.blaze.service.security.ClientConsentGate
import org.koin.dsl.module

/**
 * The consent layer: one singleton that both suspends the local-API transport on unknown clients
 * and gates extension jars, and whose pending-request queue the app-wide consent dialog renders.
 */
val securityModule = module {
    single {
        val pluginLoader = get<PluginLoader>()
        ConsentService(
            appSettingsRepository = get(),
            scope = get(),
            // Prompts read the handler's manifest straight off the zip - naming the extension and
            // its claimed URL patterns happens without loading a single plugin class. Theme jars
            // carry no resolver manifest, so they keep the plain file-name prompt.
            extensionDescriptor = { kind, jar ->
                if (kind == ConsentSubjectKind.LINK_RESOLVER) {
                    pluginLoader.peekManifest(jar)?.let { manifest ->
                        ExtensionDescriptor(
                            name = manifest.displayName,
                            author = manifest.author,
                            version = manifest.version,
                            urlPatterns = manifest.urlPatterns,
                        )
                    }
                } else null
            },
        )
    }

    // The transport only knows the port, not the app class that implements it.
    single<ClientConsentGate> { get<ConsentService>() }
}
