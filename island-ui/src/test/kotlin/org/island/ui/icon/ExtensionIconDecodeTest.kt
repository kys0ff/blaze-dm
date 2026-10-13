package org.island.ui.icon

import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Headless smoke tests for the extension-icon decode pipeline: the JSVG rasterizer must turn
 * manifest-declared SVG bytes into a real PNG the Skia path can consume, and must degrade to
 * null (placeholder in the UI) rather than throw on garbage input.
 */
class ExtensionIconDecodeTest {

    private val simpleSvg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
          <rect x="2" y="2" width="12" height="12" fill="#ff0000"/>
        </svg>
    """.trimIndent()

    private fun pngBytes(size: Int): ByteArray {
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.graphics.apply {
            color = java.awt.Color(0, 255, 0)
            fillRect(0, 0, size, size)
            dispose()
        }
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `an svg rasterizes to png bytes of the requested size`() {
        val png = assertNotNull(rasterizeSvg(simpleSvg.toByteArray(), 32))
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertEquals(32, decoded.width)
        assertEquals(32, decoded.height)

        // The 2..14 red square covers the (scaled) center of the canvas.
        val argb = decoded.getRGB(16, 16)
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        assertTrue(red > 200, "expected a red center, got #$argb")
        assertTrue(green < 60, "expected no green bleed, got #$argb")
    }

    @Test
    fun `garbage svg input yields null instead of an exception`() {
        assertNull(rasterizeSvg("this is not an svg".toByteArray(), 16))
    }

    @Test
    fun `a png icon decodes through the same entry point the composable uses`() {
        val bitmap = decodeIslandIcon(IslandIconSource(pngBytes(16), IslandIconFormat.PNG), targetPx = 16)
        assertNotNull(bitmap)
        assertEquals(16, bitmap.width)
    }

    @Test
    fun `an svg icon decodes through the same entry point the composable uses`() {
        val bitmap = decodeIslandIcon(IslandIconSource(simpleSvg.toByteArray(), IslandIconFormat.SVG), targetPx = 16)
        assertNotNull(bitmap)
        // Supersampled at 2x before the Skia decode.
        assertEquals(32, bitmap.width)
    }

    @Test
    fun `a broken svg never reaches the composable as a bitmap`() {
        assertNull(decodeIslandIcon(IslandIconSource("<nope".toByteArray(), IslandIconFormat.SVG), targetPx = 16))
    }
}
