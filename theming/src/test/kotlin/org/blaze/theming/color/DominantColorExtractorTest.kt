package org.blaze.theming.color

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DominantColorExtractorTest {

    private fun image(w: Int, h: Int, type: Int = BufferedImage.TYPE_INT_ARGB) =
        BufferedImage(w, h, type)

    /** `getGraphics()` is statically a `Graphics`, so fill via the rectangle it does have. */
    private fun BufferedImage.filled(x: Int, y: Int, w: Int, h: Int, argb: Int): BufferedImage = apply {
        val g = graphics
        g.color = Color(argb)
        g.fillRect(x, y, w, h)
        g.dispose()
    }

    @Test
    fun `a uniform image yields exactly its color`() {
        val img = image(32, 32).filled(0, 0, 32, 32, 0xFF3574F0.toInt())
        val palette = DominantColorExtractor.extract(img)

        assertEquals(1, palette.size)
        val color = palette.single()
        assertEquals(0xFF3574F0.toInt(), color.argb)
        assertEquals(1f, color.percentage, 0.001f)
        assertEquals("#3574F0", color.hex)
    }

    @Test
    fun `two far-apart regions become two clusters proportional to their area`() {
        val img = image(64, 32)
            .filled(0, 0, 48, 32, 0xFFE04040.toInt())
            .filled(48, 0, 16, 32, 0xFF4040E0.toInt())
        val palette = DominantColorExtractor.extract(img, DominantColorOptions(colorCount = 4))

        assertEquals(2, palette.size)
        // Sorted by coverage: the 3:1 red region comes first with ~75% of the samples.
        assertEquals(0xFFE04040.toInt(), palette[0].argb)
        assertEquals(0.75f, palette[0].percentage, 0.02f)
        assertEquals(0xFF4040E0.toInt(), palette[1].argb)
        assertEquals(0.25f, palette[1].percentage, 0.02f)
    }

    @Test
    fun `near-gray pixels collapse into one cluster while transparency is skipped`() {
        val img = image(16, 16) // rows 12..16 stay fully transparent: contribute nothing
            .filled(0, 0, 16, 8, 0xFF808080.toInt())
            .filled(0, 8, 16, 4, 0xFF888888.toInt())
        val palette = DominantColorExtractor.extract(img)

        // The two grays sit inside neighboring 5-bit buckets (0x80>>3 vs 0x88>>3) and the greedy
        // seeding merges them (their redmean distance is far below minDistance); the transparent
        // strip must not show up as a dark fourth region, hence 192/192 pixels on one entry.
        assertTrue(palette.all { it.saturation < 0.1f }, "expected only grays, got ${palette.map { it.hex }}")
        assertEquals(1, palette.size)
        assertEquals(192, palette.single().count)
        assertEquals(1f, palette.single().percentage, 0.01f)
    }

    @Test
    fun `sub-images sample the cropped region, not the whole buffer`() {
        val parent = image(64, 64).filled(0, 0, 64, 64, 0xFF00FF00.toInt())
        parent.filled(16, 16, 8, 8, 0xFFFF0000.toInt())
        val sub = parent.getSubimage(16, 16, 8, 8)

        val palette = DominantColorExtractor.extract(sub)
        assertEquals(1, palette.size)
        assertEquals(0xFFFF0000.toInt(), palette.single().argb)
    }

    @Test
    fun `TYPE_INT_RGB keeps its fast path honest with an opaque result`() {
        val img = image(8, 8, BufferedImage.TYPE_INT_RGB).filled(0, 0, 8, 8, 0xFF123456.toInt())
        val color = DominantColorExtractor.extract(img).single()
        assertEquals(0x123456, color.argb and 0xFFFFFF)
        assertTrue(color.argb ushr 24 == 0xFF, "results are always opaque")
    }

    @Test
    fun `raw bytes are decoded through ImageIO`() {
        val img = image(24, 24).filled(0, 0, 24, 24, 0xFFABCDEF.toInt())
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)

        val palette = DominantColorExtractor.extract(out.toByteArray())
        assertEquals(0xFFABCDEF.toInt(), palette.single().argb)
    }

    @Test
    fun `corrupt image bytes fail loudly`() {
        assertFailsWith<IOException> { DominantColorExtractor.extract("not a png".toByteArray()) }
    }

    @Test
    fun `sampling density adapts to image size without skewing the palette`() {
        // 256x256 with maxSamples 4096 => step 4; the 3:1 area ratio must survive the skipping.
        val img = image(256, 256)
            .filled(0, 0, 256, 256, 0xFF202020.toInt())
            .filled(0, 0, 192, 256, 0xFFD0D0D0.toInt())
        val palette = DominantColorExtractor.extract(img, DominantColorOptions(maxSamples = 4_096))

        val light = palette.maxByOrNull { it.red }!!
        assertTrue(light.percentage > 0.6f, "light region under-sampled: ${palette.map { it.hex to it.percentage }}")
    }

    @Test
    fun `options reject nonsense up front`() {
        assertFailsWith<IllegalArgumentException> { DominantColorOptions(colorCount = 0) }
        assertFailsWith<IllegalArgumentException> { DominantColorOptions(maxSamples = 0) }
        assertFailsWith<IllegalArgumentException> { DominantColorOptions(minDistance = -1.0) }
        assertFailsWith<IllegalArgumentException> { DominantColorOptions(minPercentage = 1.5f) }
        assertFailsWith<IllegalArgumentException> { DominantColorOptions(transparentAlpha = 256) }
        assertFailsWith<IllegalArgumentException> { DominantColorOptions(refinementPasses = -1) }
    }
}
