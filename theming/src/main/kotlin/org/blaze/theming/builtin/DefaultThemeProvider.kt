package org.blaze.theming.builtin

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.IslandsDefaults
import org.blaze.theming.api.ThemePalette
import org.blaze.theming.core.ThemeRegistry

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
class DefaultThemeProvider : BlazeThemeProvider {
    override val id: String = ThemeRegistry.DEFAULT_THEME_ID
    override val displayName: String = "Islands (default)"
    override val description: String = "The built-in JetBrains Islands palette, as measured from the reference"
    override val author: String = "Blaze"
    override val version: String = "2.0.0"

    override fun provide(dark: Boolean): ThemePalette = ThemePalette.Empty
}
