package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.blaze.presentation.theme.BlazeIslandColors.StripeSelectedDark
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * The single source of truth for the "Islands" chrome — the JetBrains New UI look where the window
 * is a neutral *frame* and every panel (editor, tool window, status widget) floats on it as a
 * rounded *island*, separated by thin gaps rather than divider lines.
 *
 * Jewel 0.41.0 ships the Islands *color ramps* (`DefaultColorPalette.IslandsDark/Light`) but not the
 * Islands *styling*, and those palettes are `@InternalJewelApi`, so the values below mirror the
 * exact palette entries the reference IDE samples to instead of opting into internals. Each constant
 * cites the Int UI token it maps to. The dark values match `docs/reference/ide-islands-dark.png`;
 * the light values are derived from the Int UI *light* palette (no light reference screenshot was
 * provided) by keeping the same island/frame contrast direction (frame one step off the content).
 *
 * Structural metrics (radius, gap) live in [org.blaze.presentation.components.BlazeLayout]; this
 * object only owns color.
 *
 * RE-SYNC NOTE: every literal below was copied verbatim from the internal `@InternalJewelApi`
 * palettes `DefaultColorPalette.IslandsDark` / `.IslandsLight` (and the shared `gray(n)` ramp)
 * as shipped in Jewel 0.41.0. They are hard-coded precisely because those
 * ramps are internal and can move without a major-version bump. When upgrading Jewel, re-diff these
 * constants against the new `IslandsDark`/`IslandsLight` `rawMap` + gray ramp and update any that
 * changed (frame/gray-30, island/gray(1), selected stripe/gray(3)).
 */
internal object BlazeIslandColors {

    // ── Dark (New UI "Islands", measured from the reference screenshot) ──────────────────────
    /** Window chrome behind islands: title bar, stripes, status bar, gaps. ~#26282c (Islands gray-30). */
    private val FrameDark = Color(0xFF26282C)
    /** Island surface (editor / tool-window body). ~#1e1f22 (Int UI Dark gray(1)). */
    private val IslandDark = Color(0xFF1E1F22)
    /** Selected tool-window stripe button: subtle gray, not accent. ~#3a3b3f (Int UI Dark gray(3)). */
    private val StripeSelectedDark = Color(0xFF393B40)
    /** Stripe button hover: a touch subtler than [StripeSelectedDark]. */
    private val StripeHoverDark = Color(0x1FFFFFFF) // ~12% white
    /** Hairline that optionally rims an island (Int UI Dark gray(3)). */
    private val IslandBorderDark = Color(0xFF393B40)

    // ── Light (derived from the Int UI light palette; no measured reference) ──────────────────
    /** Frame is one step *darker* than the white content in light mode (Int UI Light gray(13)). */
    private val FrameLight = Color(0xFFF7F8FA)
    /** Island surface = white (Int UI Light gray(14)). */
    private val IslandLight = Color(0xFFFFFFFF)
    /** Selected stripe button (Int UI Light gray(11)). */
    private val StripeSelectedLight = Color(0xFFDFE1E5)
    /** Stripe button hover: a faint darkening over the frame. */
    private val StripeHoverLight = Color(0x0F000000) // ~6% black
    /** Island rim (Int UI Light gray(9)). */
    private val IslandBorderLight = Color(0xFFC9CCD6)

    // ── Composable accessors (follow the active theme's light/dark) ──────────────────────────
    val frame: Color
        @Composable @ReadOnlyComposable get() = if (JewelTheme.isDark) FrameDark else FrameLight

    val island: Color
        @Composable @ReadOnlyComposable get() = if (JewelTheme.isDark) IslandDark else IslandLight

    val stripeSelected: Color
        @Composable @ReadOnlyComposable get() = if (JewelTheme.isDark) StripeSelectedDark else StripeSelectedLight

    val stripeHover: Color
        @Composable @ReadOnlyComposable get() = if (JewelTheme.isDark) StripeHoverDark else StripeHoverLight

    val islandBorder: Color
        @Composable @ReadOnlyComposable get() = if (JewelTheme.isDark) IslandBorderDark else IslandBorderLight

    // ── Pure accessors for non-composable wiring (e.g. building the ThemeDefinition) ──────────
    fun frameFor(isDark: Boolean): Color = if (isDark) FrameDark else FrameLight
    fun islandFor(isDark: Boolean): Color = if (isDark) IslandDark else IslandLight
}
