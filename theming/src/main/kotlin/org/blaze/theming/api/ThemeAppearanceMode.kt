package org.blaze.theming.api

/**
 * Which appearance modes a theme actually paints. The engine resolves the user's
 * light/dark/system choice to exactly one of these before asking for a palette, so a theme
 * never sees `SYSTEM`.
 */
enum class ThemeAppearanceMode {
    LIGHT,
    DARK,
    ;

    companion object {
        /** Both modes — what a theme supports unless it says otherwise. */
        val ALL: Set<ThemeAppearanceMode> = setOf(LIGHT, DARK)
    }
}
