package org.blaze.theming.api

/**
 * The Islands design system's own tokens — the JetBrains New UI "Islands" shell Blaze renders
 * with, where the window is a neutral *frame* and every panel floats on it as a rounded
 * *island* separated by thin gaps rather than divider lines.
 *
 * These are deliberately separate from [ThemePalette]'s Jewel-mapped colors: Jewel only knows
 * "panel background", while Islands needs the whole chrome vocabulary (frame vs. surface vs.
 * stripe states vs. rim vs. ambient glow). A theme overrides any subset of them; a null field
 * keeps the app's stock Islands value (see [IslandsDefaults]).
 *
 * Like [ThemePalette], values are packed ARGB `Long`s so plugin jars never touch Compose.
 */
data class IslandsPalette(
    /** Window chrome behind islands: title bar, stripe, status bar, gaps. */
    val frameArgb: Long? = null,
    /** Island surface (editor / tool-window body). */
    val surfaceArgb: Long? = null,
    /** Selected tool-window stripe button (a subtle gray in the stock design, never accent). */
    val stripeSelectedArgb: Long? = null,
    /** Stripe button hover, a touch subtler than [stripeSelectedArgb]. */
    val stripeHoverArgb: Long? = null,
    /** Hairline that optionally rims an island. */
    val borderArgb: Long? = null,
    /**
     * The color the ambient chrome glow is tinted with. Null means "follow the theme's accent,
     * or the brand icon's dominant color when one is available" — the app's default behavior.
     */
    val ambientAccentArgb: Long? = null,
    /**
     * Peak blend of the glow, percent over the frame color (0 = off, 100 = full accent).
     * Null keeps the app's measured default per light/dark mode; the resolved value is clamped
     * into `0..100` before use.
     */
    val ambientIntensityPercent: Int? = null,
) {
    companion object {
        /** A palette that overrides nothing — every token falls back to [IslandsDefaults]. */
        val Empty = IslandsPalette()
    }
}

/**
 * Overlay the tokens [other] sets onto this palette; fields [other] leaves null keep theirs.
 * Order is "receiver = fallback", matching the theme-over-default composition rule.
 */
fun IslandsPalette.withFallback(other: IslandsPalette): IslandsPalette = IslandsPalette(
    frameArgb = frameArgb ?: other.frameArgb,
    surfaceArgb = surfaceArgb ?: other.surfaceArgb,
    stripeSelectedArgb = stripeSelectedArgb ?: other.stripeSelectedArgb,
    stripeHoverArgb = stripeHoverArgb ?: other.stripeHoverArgb,
    borderArgb = borderArgb ?: other.borderArgb,
    ambientAccentArgb = ambientAccentArgb ?: other.ambientAccentArgb,
    ambientIntensityPercent = ambientIntensityPercent ?: other.ambientIntensityPercent,
)

/** Complete every unset token from the stock [IslandsDefaults] for [dark]. */
fun IslandsPalette.filledWithIslandsDefaults(dark: Boolean): IslandsPalette =
    withFallback(IslandsDefaults.forMode(dark))
