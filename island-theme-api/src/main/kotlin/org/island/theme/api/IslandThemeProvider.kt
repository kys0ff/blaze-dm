package org.island.theme.api

/**
 * Contract that a colour theme ("extension") implements to customise Blaze's appearance.
 *
 * Blaze renders exactly one design system — the JetBrains New UI "Islands" shell — and a theme
 * is an overlay of [ThemePalette] tokens on top of its stock values ([IslandsDefaults]), never a
 * replacement of the shell. Themes are discovered in two ways:
 *  - Built-in: bundled with the app and registered directly in the DI graph.
 *  - Plugin: compiled against this module and dropped into the themes directory as a jar
 *    providing a `META-INF/services/org.island.theme.api.IslandThemeProvider` entry so they
 *    can be loaded through [java.util.ServiceLoader].
 *
 * [provide] is intentionally free of any Compose/Jewel type: it returns a plain
 * [ThemePalette] of packed ARGB numbers, which keeps third-party jars free of a UI
 * dependency and lets the app decide how (and where) the colours are applied.
 *
 * Every metadata member beyond [description] has a default, so jars compiled against the
 * original three-property contract keep loading unchanged.
 */
interface IslandThemeProvider {
    /** Stable, unique identifier. Used to persist the user's selection. */
    val id: String

    /** Human-readable name shown in the theme picker and settings (e.g. "Midnight Teal"). */
    val displayName: String

    /** Short one-line description of the theme. */
    val description: String

    /** Optional author/vendor credit, shown under the theme's name in the picker. */
    val author: String get() = ""

    /** Optional theme version (e.g. "1.2.0"); informational only. */
    val version: String get() = ""

    /**
     * The appearance modes this theme actually paints. The registry lists the theme for a mode
     * only when it declares support for that mode, and falls back to the built-in default when
     * the user lands on an unsupported one — so a dark-only theme never has its dark palette
     * stretched over light chrome.
     */
    val supportedModes: Set<ThemeAppearanceMode> get() = ThemeAppearanceMode.ALL

    /**
     * Returns the palette to apply for the given appearance mode. [dark] is the resolved
     * light/dark choice (system mode already collapsed to one). Return [ThemePalette.Empty]
     * (or leave fields null) to keep the app defaults for that mode.
     */
    fun provide(dark: Boolean): ThemePalette
}
