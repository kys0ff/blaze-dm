package org.blaze.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.blaze.security.ConsentService
import org.blaze.security.ConsentSubjectKind
import org.island.theme.builtin.DefaultThemeProvider
import org.island.theme.builtin.MidnightThemeProvider
import org.island.theme.builtin.TealThemeProvider
import org.island.theme.core.ThemePluginLoader
import org.island.theme.core.ThemeRegistry
import org.island.theme.core.ThemeSettingsRepository
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference

val themeModule = module {
    single { ThemePluginLoader() }

    single { ThemeSettingsRepository(get()) }

    single {
        // Same consent gate as the link-handler registry: a theme jar is a program, and it does not
        // run until the user said so; approving a review reloads this registry so the theme appears.
        // The instance is captured in a holder because the registry's init reload runs while Koin is
        // still inside this definition, and the review is dispatched on the app scope.
        val consent = get<ConsentService>()
        val appScope = get<CoroutineScope>()
        val holder = AtomicReference<ThemeRegistry>()
        val registry = ThemeRegistry(
            builtIns = listOf(DefaultThemeProvider(), TealThemeProvider(), MidnightThemeProvider()),
            pluginLoader = get(),
            settingsRepository = get(),
            jarTrust = { jar -> consent.isExtensionTrusted(ConsentSubjectKind.THEME, jar) },
            onUntrustedJars = { jars ->
                appScope.launch {
                    consent.requestExtensionReview(ConsentSubjectKind.THEME, jars) {
                        holder.get()?.reload()
                    }
                }
            },
        )
        holder.set(registry)
        registry
    }
}
