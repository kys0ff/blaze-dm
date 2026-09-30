package org.blaze.presentation.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the ambient glow tint math. The peak tint is a pure linear blend of the frame toward the
 * accent by the theme's resolved intensity [fraction], so it can never leave the per-channel segment
 * between the two colors (which is what keeps the glow a *tint* rather than a new surface), it must
 * be a no-op at zero and reach the accent at one, and it must preserve the base's alpha.
 */
class AmbientTitleBarTest {

    private val darkBase = Color(0xFF26282C) // Islands dark frame
    private val accent = Color(0xFF3574F0) // Int UI blue

    @Test
    fun `the tint is a subtle shift of the frame toward the accent`() {
        val tint = ambientTintColor(darkBase, accent, fraction = 0.15f)
        // A plain linear blend can never leave the per-channel segment between the two colors.
        assertTrue(tint.red in segment(darkBase.red, accent.red))
        assertTrue(tint.green in segment(darkBase.green, accent.green))
        assertTrue(tint.blue in segment(darkBase.blue, accent.blue))
        // ...and for this concrete blue accent it reads as the expected cool cast.
        assertTrue(tint.blue > darkBase.blue, "a blue tint must pull blue up")
        // A small fraction must stay subtle.
        assertTrue((darkBase.blue - tint.blue).let { kotlin.math.abs(it) } < 0.20)
    }

    @Test
    fun `fraction interpolates linearly between frame and accent`() {
        assertEquals(darkBase, ambientTintColor(darkBase, accent, fraction = 0f))
        assertEquals(
            ambientTintColor(darkBase, accent, fraction = 0.5f),
            Color(
                red = (darkBase.red + accent.red) / 2f,
                green = (darkBase.green + accent.green) / 2f,
                blue = (darkBase.blue + accent.blue) / 2f,
                alpha = darkBase.alpha,
            ),
        )
        assertEquals(accent.copy(alpha = darkBase.alpha), ambientTintColor(darkBase, accent, fraction = 1f))
    }

    @Test
    fun `blending with the frame itself changes nothing`() {
        val tint = ambientTintColor(darkBase, darkBase, fraction = 0.75f)
        assertEquals(darkBase.red.toDouble(), tint.red.toDouble(), 1e-6)
        assertEquals(darkBase.green.toDouble(), tint.green.toDouble(), 1e-6)
        assertEquals(darkBase.blue.toDouble(), tint.blue.toDouble(), 1e-6)
    }

    @Test
    fun `the base alpha is preserved`() {
        val translucent = Color(0x8026282C)
        assertEquals(translucent.alpha, ambientTintColor(translucent, accent, fraction = 0.4f).alpha)
    }

    /** The closed segment between the two channel values a linear blend must stay inside. */
    private fun segment(from: Float, to: Float): ClosedFloatingPointRange<Float> =
        minOf(from, to)..maxOf(from, to)
}
