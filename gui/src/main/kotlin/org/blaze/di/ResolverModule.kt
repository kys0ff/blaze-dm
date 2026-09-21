package org.blaze.di

import org.blaze.resolver.builtin.MediafireResolver
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.PluginLoader
import org.koin.dsl.module

val resolverModule = module {
    single { PluginLoader() }

    single { LinkResolverSettingsRepository(get()) }

    single {
        LinkResolverRegistry(
            builtIns = listOf(MediafireResolver()),
            pluginLoader = get(),
            settingsRepository = get()
        )
    }
}
