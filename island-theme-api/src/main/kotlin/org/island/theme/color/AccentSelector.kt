package org.island.theme.color

import kotlin.math.sqrt

/**
 * Picks the one palette entry best used as a UI *accent* (title-bar glow, highlights).
 *
 * The most *frequent* color of an artwork is usually its background or outline, not its brand
 * color - so instead of taking colors[0], candidates are scored on how colorful and how present
 * they are, and washed-out entries are dropped outright:
 *
 *  - [DominantColor.saturation] must clear [minSaturation] (grays carry no brand signal), and
 *  - [DominantColor.brightness] must sit in [minBrightness]..1 so near-black strokes and
 *    near-white paper never win, no matter how common they are.
 *
 * The final score multiplies saturation by the square root of coverage: a vivid color keeps a
 * claim even when it occupies a minority of the image (like a flame inside a dark icon), but a
 * barely-present speck still loses to a strongly similar color that is everywhere.
 *
 * Returns null when nothing clears the bar, so callers can fall back to their own default.
 */
object AccentSelector {

    fun select(
        colors: List<DominantColor>,
        minSaturation: Float = 0.30f,
        minBrightness: Float = 0.35f,
    ): DominantColor? = colors
        .asSequence()
        .filter { it.saturation >= minSaturation && it.brightness >= minBrightness }
        .maxByOrNull { it.saturation * sqrt(it.percentage) }

    /** [select] with the raw ARGB unpacked for direct use by UI layers; null when no candidate. */
    fun selectArgb(colors: List<DominantColor>): Int? = select(colors)?.argb
}
