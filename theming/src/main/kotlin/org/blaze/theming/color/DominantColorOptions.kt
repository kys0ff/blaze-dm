package org.blaze.theming.color

/** Tuning knobs for [DominantColors.extract]; every default suits small brand artwork like app icons. */
data class DominantColorOptions(
    /** Max number of colors to return (fewer if the image is uniform). */
    val colorCount: Int = 5,
    /** Approximate number of pixels to sample; the step is derived from image size. */
    val maxSamples: Int = 20_000,
    /** Minimum "red-mean" distance between two palette colors. Higher = more distinct palette. */
    val minDistance: Double = 28.0,
    /** Drop colors that cover less than this fraction of sampled pixels (0..1). */
    val minPercentage: Float = 0f,
    /** Pixels with alpha below this are ignored. */
    val transparentAlpha: Int = 32,
    /** Extra k-means style refinement passes after the initial assignment. */
    val refinementPasses: Int = 2,
) {
    init {
        require(colorCount > 0) { "colorCount must be > 0" }
        require(maxSamples > 0) { "maxSamples must be > 0" }
        require(minDistance >= 0.0) { "minDistance must be >= 0" }
        require(minPercentage in 0f..1f) { "minPercentage must be in 0..1" }
        require(transparentAlpha in 0..255) { "transparentAlpha must be in 0..255" }
        require(refinementPasses >= 0) { "refinementPasses must be >= 0" }
    }
}
