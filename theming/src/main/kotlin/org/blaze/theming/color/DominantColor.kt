package org.blaze.theming.color

/**
 * One palette entry produced by [DominantColors]: an approximate color of the sampled image
 * plus how much of the sampled pixel mass it carries.
 *
 * Framework-agnostic by module convention - the packed value lives here as a plain [Int]
 * (0xAARRGGBB, always opaque) and the UI layer decides how to turn it into a real color.
 */
data class DominantColor(
    val argb: Int,
    val count: Int,
    /** Share of the *sampled* pixels assigned to this entry (0..1). */
    val percentage: Float,
) {
    val red: Int get() = (argb shr 16) and 0xFF
    val green: Int get() = (argb shr 8) and 0xFF
    val blue: Int get() = argb and 0xFF

    /** "#RRGGBB" */
    val hex: String get() = "#%02X%02X%02X".format(red, green, blue)

    /** HSV saturation (0..1): how colorful, versus how gray, this entry is. */
    val saturation: Float
        get() {
            val cMax = maxOf(red, green, blue)
            val cMin = minOf(red, green, blue)
            return if (cMax == 0) 0f else (cMax - cMin).toFloat() / cMax
        }

    /** HSV value (0..1): how bright this entry is. */
    val brightness: Float
        get() = maxOf(red, green, blue) / 255f
}
