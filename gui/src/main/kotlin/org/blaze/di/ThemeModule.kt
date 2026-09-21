package org.blaze.di

import org.blaze.theming.builtin.DefaultThemeProvider
import org.blaze.theming.builtin.TealThemeProvider
import org.blaze.theming.core.ThemePluginLoader
import org.blaze.theming.core.ThemeRegistry
import org.blaze.theming.core.ThemeSettingsRepository
import org.koin.dsl.module

val themeModule = module {
    single { ThemePluginLoader() }

    single { ThemeSettingsRepository(get()) }

    single {
        ThemeRegistry(
            builtIns = listOf(DefaultThemeProvider(), TealThemeProvider()),
            pluginLoader = get(),
            settingsRepository = get()
        )
    }
}
