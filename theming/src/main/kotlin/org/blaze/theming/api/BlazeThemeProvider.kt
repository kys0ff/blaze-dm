package org.blaze.theming.api

/**
 * Contract that a colour theme ("extension") implements to customise Blaze's appearance.
 *
 * Themes are discovered in two ways:
 *  - Built-in: bundled with the app and registered directly in the DI graph.
 *  - Plugin: compiled against this module and dropped into the themes directory as a jar
 *    providing a `META-INF/services/org.blaze.theming.api.BlazeThemeProvider` entry so they
 *    can be loaded through [java.util.ServiceLoader].
 *
 * [provide] is intentionally free of any Compose/Jewel type: it returns a plain
 * [ThemePalette] of packed ARGB numbers, which keeps third-party jars free of a UI
 * dependency and lets the app decide how (and where) the colours are applied.
 */
interface BlazeThemeProvider {
    /** Stable, unique identifier. Used to persist the user's selection. */
    val id: String

    /** Human-readable name shown in the theme picker and settings (e.g. "Midnight Teal"). */
    val displayName: String

    /** Short one-line description of the theme. */
    val description: String

    /**
     * Returns the palette to apply for the given appearance mode. [dark] is the resolved
     * light/dark choice (system mode already collapsed to one). Return [ThemePalette.Empty]
     * (or leave fields null) to keep the app defaults for that mode.
     */
    fun provide(dark: Boolean): ThemePalette
}
