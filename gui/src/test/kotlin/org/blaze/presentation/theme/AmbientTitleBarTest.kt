package org.blaze.presentation.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the ambient title-bar tint math: it must stay a *subtle* shift of the bar background
 * toward the accent (never past it), lean harder in dark themes like IntelliJ's darker ambient,
 * and be a no-op when there is nothing to blend.
 */
class AmbientTitleBarTest {

    private val lightBase = Color(0xFFF2F3F5) // Int UI light panel-ish title bar background
    private val darkBase = Color(0xFF3C3F42) // Darcula-style background
    private val accent = Color(0xFF3574F0) // Int UI blue

    @Test
    fun `the ambient center is a subtle shift of the background toward the accent`() {
        val center = ambientTitleBarCenterColor(lightBase, accent, isDark = false)
        // A plain linear blend can never leave the per-channel segment between the two colors.
        assertTrue(center.red in segment(lightBase.red, accent.red))
        assertTrue(center.green in segment(lightBase.green, accent.green))
        assertTrue(center.blue in segment(lightBase.blue, accent.blue))
        // ...and for this concrete blue accent it reads as the expected cool cast: the warm
        // channels recede and blue becomes the dominant one.
        assertTrue(center.red < lightBase.red, "a blue tint must pull red down")
        assertTrue(center.green < lightBase.green, "a blue tint must pull green down")
        assertTrue(center.blue > center.green, "blue must become the dominant channel")
        assertTrue(
            (lightBase.red - center.red) < 0.20,
            "the light-theme cast must stay subtle, got delta=${lightBase.red - center.red}",
        )
    }

    @Test
    fun `dark themes lean harder into the tint than light ones`() {
        val light = ambientTitleBarCenterColor(darkBase, accent, isDark = false)
        val dark = ambientTitleBarCenterColor(darkBase, accent, isDark = true)
        assertTrue(dark.blue > light.blue, "dark ambient must reach further toward the accent blue")
        assertTrue(dark.red < light.red, "dark ambient must pull further away from the neutral base")
    }

    @Test
    fun `blending with the background itself changes nothing`() {
        val center = ambientTitleBarCenterColor(darkBase, darkBase, isDark = true)
        assertEquals(darkBase.red.toDouble(), center.red.toDouble(), 1e-6)
        assertEquals(darkBase.green.toDouble(), center.green.toDouble(), 1e-6)
        assertEquals(darkBase.blue.toDouble(), center.blue.toDouble(), 1e-6)
    }

    /** The closed segment between the two channel values a linear blend must stay inside. */
    private fun segment(from: Float, to: Float): ClosedFloatingPointRange<Float> =
        minOf(from, to)..maxOf(from, to)
}
