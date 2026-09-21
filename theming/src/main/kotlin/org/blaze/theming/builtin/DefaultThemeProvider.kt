package org.blaze.theming.builtin

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette
import org.blaze.theming.core.ThemeRegistry

/**
 * The stock Blaze look. It overrides nothing, so every color falls back to the app's
 * built-in defaults in the GUI layer. Registered under [ThemeRegistry.DEFAULT_THEME_ID],
 * which the registry uses as the fallback palette for partial themes.
 */
class DefaultThemeProvider : BlazeThemeProvider {
    override val id: String = ThemeRegistry.DEFAULT_THEME_ID
    override val displayName: String = "Int UI (default)"
    override val description: String = "The built-in IntelliJ-style palette"

    override fun provide(dark: Boolean): ThemePalette = ThemePalette.Empty
}
