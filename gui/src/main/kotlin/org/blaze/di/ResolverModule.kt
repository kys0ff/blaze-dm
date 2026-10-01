package org.blaze.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.blaze.resolver.builtin.mediafire.MediafireResolver
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.PluginLoader
import org.blaze.security.ConsentService
import org.blaze.security.ConsentSubjectKind
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference

val resolverModule = module {
    single { PluginLoader() }

    single { LinkResolverSettingsRepository(get()) }

    single {
        // The consent layer sits in front of every plugin jar: an untrusted one is skipped by the
        // loader (its code never runs) and handed to ConsentService as a review request, which asks
        // the user and reloads the registry the moment the jar is approved.
        val consent = get<ConsentService>()
        val appScope = get<CoroutineScope>()
        // The registry's own init reload runs *while* it is being built, so the review callback cannot
        // resolve LinkResolverRegistry back through Koin (a re-entrant get inside its own definition).
        // The instance is captured here instead, and the review itself is dispatched on the app scope.
        val holder = AtomicReference<LinkResolverRegistry>()
        val registry = LinkResolverRegistry(
            builtIns = listOf(MediafireResolver()),
            pluginLoader = get(),
            settingsRepository = get(),
            jarTrust = { jar -> consent.isExtensionTrusted(ConsentSubjectKind.LINK_RESOLVER, jar) },
            onUntrustedJars = { jars ->
                appScope.launch {
                    consent.requestExtensionReview(ConsentSubjectKind.LINK_RESOLVER, jars) {
                        holder.get()?.reload()
                    }
                }
            },
        )
        holder.set(registry)
        registry
    }
}
