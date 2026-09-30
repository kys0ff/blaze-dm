package org.blaze.theming.api

/**
 * The stock values of the Islands design system — the fallback every [IslandsPalette] field
 * resolves against, and the single source of truth for the chrome's neutral ramp.
 *
 * The dark values match `docs/reference/ide-islands-dark.png`; the light values are derived
 * from the Int UI *light* palette (no light reference screenshot was provided) by keeping the
 * same island/frame contrast direction (frame one step off the content).
 *
 * RE-SYNC NOTE: every literal below was copied verbatim from the internal `@InternalJewelApi`
 * palettes `DefaultColorPalette.IslandsDark` / `.IslandsLight` (and the shared `gray(n)` ramp)
 * as shipped in Jewel 0.41.0. They are hard-coded precisely because those ramps are internal
 * and can move without a major-version bump. When upgrading Jewel, re-diff these constants
 * against the new `IslandsDark`/`IslandsLight` `rawMap` + gray ramp and update any that
 * changed (frame/gray-30, island/gray(1), selected stripe/gray(3)).
 *
 * This object lives in the contract module (not the GUI) so a plugin can build a partial
 * override from real values — e.g. "keep the stock frame, restyle only the surface" — without
 * duplicating literals it cannot observe.
 */
object IslandsDefaults {

    // ── Dark (New UI "Islands", measured from the reference screenshot) ──────────────────────
    /** Window chrome behind islands: title bar, stripes, status bar, gaps. ~#26282c (Islands gray-30). */
    const val FRAME_DARK: Long = 0xFF26282C

    /** Island surface (editor / tool-window body). ~#1e1f22 (Int UI Dark gray(1)). */
    const val SURFACE_DARK: Long = 0xFF1E1F22

    /** Selected tool-window stripe button: subtle gray, not accent. ~#3a3b3f (Int UI Dark gray(3)). */
    const val STRIPE_SELECTED_DARK: Long = 0xFF393B40

    /** Stripe button hover: a touch subtler than [STRIPE_SELECTED_DARK]. ~12% white. */
    const val STRIPE_HOVER_DARK: Long = 0x1FFFFFFF

    /** Hairline that optionally rims an island (Int UI Dark gray(3)). */
    const val BORDER_DARK: Long = 0xFF393B40

    // ── Light (derived from the Int UI light palette; no measured reference) ──────────────────
    /** Frame is one step *darker* than the white content in light mode (Int UI Light gray(13)). */
    const val FRAME_LIGHT: Long = 0xFFF7F8FA

    /** Island surface = white (Int UI Light gray(14)). */
    const val SURFACE_LIGHT: Long = 0xFFFFFFFF

    /** Selected stripe button (Int UI Light gray(11)). */
    const val STRIPE_SELECTED_LIGHT: Long = 0xFFDFE1E5

    /** Stripe button hover: a faint darkening over the frame. ~6% black. */
    const val STRIPE_HOVER_LIGHT: Long = 0x0F000000

    /** Island rim (Int UI Light gray(9)). */
    const val BORDER_LIGHT: Long = 0xFFC9CCD6

    // ── Ambient chrome glow (measured off the same reference) ─────────────────────────────────
    /** Peak blend of the accent into the frame, dark mode: 15% (the reference's ~19-unit lift). */
    const val AMBIENT_INTENSITY_DARK_PERCENT: Int = 15

    /** Peak blend of the accent into the frame, light mode: 8%. */
    const val AMBIENT_INTENSITY_LIGHT_PERCENT: Int = 8

    /** The stock Islands values for the given appearance mode. */
    fun forMode(dark: Boolean): IslandsPalette = if (dark) {
        IslandsPalette(
            frameArgb = FRAME_DARK,
            surfaceArgb = SURFACE_DARK,
            stripeSelectedArgb = STRIPE_SELECTED_DARK,
            stripeHoverArgb = STRIPE_HOVER_DARK,
            borderArgb = BORDER_DARK,
            ambientIntensityPercent = AMBIENT_INTENSITY_DARK_PERCENT,
        )
    } else {
        IslandsPalette(
            frameArgb = FRAME_LIGHT,
            surfaceArgb = SURFACE_LIGHT,
            stripeSelectedArgb = STRIPE_SELECTED_LIGHT,
            stripeHoverArgb = STRIPE_HOVER_LIGHT,
            borderArgb = BORDER_LIGHT,
            ambientIntensityPercent = AMBIENT_INTENSITY_LIGHT_PERCENT,
        )
    }
}
