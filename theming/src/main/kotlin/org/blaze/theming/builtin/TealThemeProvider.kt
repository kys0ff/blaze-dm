package org.blaze.theming.builtin

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette

/**
 * A ready-to-use alternative accent, shipped as a built-in so the theme picker has more
 * than one option out of the box. It only overrides the accent and the semantic state
 * colors, leaving surfaces and text to fall back to the default palette.
 */
class TealThemeProvider : BlazeThemeProvider {
    override val id: String = "teal"
    override val displayName: String = "Teal"
    override val description: String = "A calmer teal accent"

    override fun provide(dark: Boolean): ThemePalette = if (dark) {
        ThemePalette(
            accentArgb = 0xFF3FB6A8,
            successArgb = 0xFF5FB865,
            warningArgb = 0xFFF2C55C,
        )
    } else {
        ThemePalette(
            accentArgb = 0xFF0F9D8C,
            successArgb = 0xFF208A3C,
            warningArgb = 0xFFA46704,
        )
    }
}
