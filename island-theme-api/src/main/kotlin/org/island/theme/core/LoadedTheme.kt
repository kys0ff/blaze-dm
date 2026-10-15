package org.island.theme.core

import org.island.theme.api.IslandThemeProvider
import java.nio.file.Path

/** Where a theme came from. */
enum class ThemeSource { BUILTIN, PLUGIN }

/** A [IslandThemeProvider] together with the provenance the UI needs. */
data class LoadedTheme(
    val provider: IslandThemeProvider,
    val source: ThemeSource,
    /** Path to the jar this came from, or null for built-ins. */
    val pluginPath: Path? = null
)
