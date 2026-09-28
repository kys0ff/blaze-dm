package org.blaze.presentation.theme

import androidx.compose.ui.graphics.toArgb
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the brand-accent pipeline: the pure bytes -> dominant palette -> vivid accent path,
 * plus the guarantee that the real bundled icon it runs against in the app is decodable and
 * carries a usable brand color.
 */
class BrandIconAccentTest {

    private fun BufferedImage.fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) = apply {
        val g = graphics
        g.color = Color(argb)
        g.fillRect(x, y, w, h)
        g.dispose()
    }

    /** Flat artwork keeps the centroids exact, so the picked accent is assertable to the bit. */
    @Test
    fun `a dark shell with a vivid flame yields the flame as the brand accent`() {
        val icon = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
            .fillRect(8, 8, 48, 48, 0xFF303030.toInt())
            .fillRect(24, 20, 16, 24, 0xFFFF6A1A.toInt()) // the minority "flame"
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(icon, "png", out)

        val accent = BrandIconAccent.deriveAccent(out.toByteArray())

        // Same Color(Int) construction path on both sides, so the round trip is bit-exact;
        // `toArgb()` keeps the 0xFF alpha the extractor always emits.
        assertEquals(0xFFFF6A1A.toInt(), accent?.toArgb(), "accent must be the exact flame color")
    }

    @Test
    fun `achromatic artwork produces no brand accent, leaving the theme accent in charge`() {
        val icon = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            .fillRect(0, 0, 32, 32, 0xFF404040.toInt())
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(icon, "png", out)

        assertEquals(null, BrandIconAccent.deriveAccent(out.toByteArray()))
    }

    @Test
    fun `undecodable bytes fail instead of inventing a color`() {
        assertFailsWith<java.io.IOException> { BrandIconAccent.deriveAccent("nope".toByteArray()) }
    }

    @Test
    fun `the real bundled icon is on the classpath and carries a brand accent`() {
        val bytes = assertNotNull(
            BrandIconAccentTest::class.java.classLoader.getResourceAsStream(BrandIconAccent.ICON_RESOURCE),
            "${BrandIconAccent.ICON_RESOURCE} must ship on the classpath",
        ).readBytes()

        val accent = assertNotNull(BrandIconAccent.deriveAccent(bytes), "the app icon must expose a vivid brand color (the ambient tint depends on it)")
        // The selector only ever passes entries with real chroma; verify that contract here in
        // channel terms so a future grayscale re-brand fails this test instead of the title bar.
        val argb = accent.toArgb()
        val channels = listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        assertTrue(
            channels.max() - channels.min() >= 60,
            "brand accent is washed out: ${"#%06X".format(argb and 0xFFFFFF)}",
        )
    }
}
