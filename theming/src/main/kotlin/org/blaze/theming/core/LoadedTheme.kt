package org.blaze.theming.core

import org.blaze.theming.api.BlazeThemeProvider
import java.nio.file.Path

/** Where a theme came from. */
enum class ThemeSource { BUILTIN, PLUGIN }

/** A [BlazeThemeProvider] together with the provenance the UI needs. */
data class LoadedTheme(
    val provider: BlazeThemeProvider,
    val source: ThemeSource,
    /** Path to the jar this came from, or null for built-ins. */
    val pluginPath: Path? = null
)
