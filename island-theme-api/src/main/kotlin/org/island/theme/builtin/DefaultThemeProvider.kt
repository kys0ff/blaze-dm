package org.island.theme.builtin

import org.island.theme.api.IslandThemeProvider
import org.island.theme.api.IslandsDefaults
import org.island.theme.api.ThemePalette
import org.island.theme.core.ThemeRegistry

/**
 * The stock Blaze look: the JetBrains New UI "Islands" shell exactly as measured off the design
 * reference, overriding nothing. It is registered under [ThemeRegistry.DEFAULT_THEME_ID], which
 * the registry uses as the fallback layer for every partial theme, so its "empty" palette is what
 * a token a plugin doesn't paint resolves against.
 *
 * A theme that wants to keep the shell and only change one thing should stay this minimal —
 * [IslandsDefaults] publishes the stock chrome values, so overriding a single token (an accent,
 * the frame) is one line and inherits every other measured value for free.
 */
class DefaultThemeProvider : IslandThemeProvider {
    override val id: String = ThemeRegistry.DEFAULT_THEME_ID
    override val displayName: String = "Islands (default)"
    override val description: String = "The built-in JetBrains Islands palette, as measured from the reference"
    override val author: String = "Island"
    override val version: String = "2.0.0"

    override fun provide(dark: Boolean): ThemePalette = ThemePalette.Empty
}
