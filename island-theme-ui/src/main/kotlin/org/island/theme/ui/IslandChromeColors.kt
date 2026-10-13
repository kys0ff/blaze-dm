package org.island.theme.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.island.theme.api.IslandsDefaults
import org.island.theme.api.IslandsPalette

/**
 * The fully-resolved Islands palette for the current composition — every token set, because
 * [IslandTheme] completes it from [IslandsDefaults] before providing it. Read this through
 * [IslandChromeColors] rather than directly, the same way [IslandSemanticColors] wraps [LocalThemePalette].
 *
 * No default is offered on purpose: an app composition always sits under [IslandTheme], and a
 * chrome read from outside it (a stray preview, a test harness) is a wiring bug worth naming.
 */
val LocalIslandsPalette = staticCompositionLocalOf<IslandsPalette> {
    error("Islands chrome read outside IslandTheme: no resolved Islands palette was provided")
}

/**
 * The single source of truth for the "Islands" chrome — the JetBrains New UI look where the window
 * is a neutral *frame* and every panel (editor, tool window, status widget) floats on it as a
 * rounded *island*, separated by thin gaps rather than divider lines.
 *
 * Blaze renders this shell and nothing else; the values below are the *tokens* of that shell, and
 * every one of them is theme-overridable. They resolve through the active [ThemePalette]'s
 * [IslandsPalette] layer (installed by [IslandTheme]), so a built-in or a plugin theme — or a colour
 * the user picked in Settings → Themes → Options — restyles the frame, the islands, the stripe
 * states, the rim and the ambient glow without a single call site changing.
 *
 * The stock (no-theme) values live in [IslandsDefaults] in the contract module, together with the
 * Jewel-token citations and the Jewel-upgrade re-sync note; structural metrics (radius, gap) live
 * in [org.island.theme.ui.IslandLayout]. This object owns only the Compose-facing
 * read of the resolved palette.
 */
object IslandChromeColors {
    private val resolved: IslandsPalette
        @Composable @ReadOnlyComposable get() = LocalIslandsPalette.current

    /** Window chrome behind islands: title bar, stripes, status bar, gaps. */
    val frame: Color
        @Composable @ReadOnlyComposable get() = resolved.frameArgb.toIslandColor()

    /** Island surface (editor / tool-window body). */
    val island: Color
        @Composable @ReadOnlyComposable get() = resolved.surfaceArgb.toIslandColor()

    /** Selected tool-window stripe button: subtle gray in the stock design, never accent. */
    val stripeSelected: Color
        @Composable @ReadOnlyComposable get() = resolved.stripeSelectedArgb.toIslandColor()

    /** Stripe button hover: a touch subtler than [stripeSelected]. */
    val stripeHover: Color
        @Composable @ReadOnlyComposable get() = resolved.stripeHoverArgb.toIslandColor()

    /** Hairline that optionally rims an island. */
    val islandBorder: Color
        @Composable @ReadOnlyComposable get() = resolved.borderArgb.toIslandColor()

    /**
     * The color the ambient glow is tinted with, or null when the theme doesn't name one — the
     * caller then falls back to the brand icon's accent, and finally to the theme accent.
     */
    val ambientAccent: Color?
        @Composable @ReadOnlyComposable get() = resolved.ambientAccentArgb?.toColor()

    /** Peak blend of the glow into the frame, 0..1 (the palette carries a percent). */
    val ambientIntensity: Float
        @Composable @ReadOnlyComposable
        get() = (resolved.ambientIntensityPercent ?: 0) / 100f
}

/**
 * A resolved token is never null ([IslandTheme] completes the palette), so the fallback only
 * exists to keep the reads total; it deliberately points at the island color, the closest
 * surface any of these slots could stand in for.
 */
private fun Long?.toIslandColor(): Color =
    this?.toColor() ?: IslandsDefaults.SURFACE_DARK.toColor()
