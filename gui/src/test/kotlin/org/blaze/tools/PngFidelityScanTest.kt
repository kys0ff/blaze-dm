package org.blaze.tools

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two chrome rules the round-3 regressions demanded: a stray full-width hairline (the `#eeeeee`
 * line the AWT panel leaked through the title bar's unpainted border row) and a surface painted with
 * another region's color.
 *
 * These are the *only* way those bugs get caught before a human looks at a PNG, so the checks
 * themselves are pinned here: one fixture proves the detector fires on the real bug, and the others
 * prove it stays quiet on a clean render, on a gradient and on content that is neither a line nor a
 * surface.
 */
class PngFidelityScanTest {

    private fun image(width: Int, height: Int, fill: (x: Int, y: Int) -> Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, fill(x, y))
        }

    /** Flat, full-width band of one color: the clean baseline every case starts from. */
    private fun flat(width: Int = 400, height: Int = 200, rgb: Int = 0x26282C): BufferedImage =
        image(width, height) { _, _ -> rgb }

    private fun rulesOf(image: BufferedImage, shot: String) =
        PngFidelityScan.scan(image, shot, "$shot.png")

    @Test
    fun `clean surface has no issues`() {
        assertEquals(emptyList(), rulesOf(flat(), "01-downloads-table"))
    }

    @Test
    fun `full width light line across the dark frame is reported - the round 3 bug`() {
        val leaked = image(400, 200) { _, y -> if (y == 41) 0xEEEEEE else 0x26282C }

        val issues = rulesOf(leaked, "10-shell")
        assertEquals(1, issues.size, "the leaked AWT row must be reported")
        with(issues.first()) {
            assertEquals("stray-line", rule)
            assertTrue(detail.contains("y=41"), detail)
            assertTrue(detail.contains("#eeeeee"), detail)
        }
    }

    @Test
    fun `an intentional whitelisted hairline is not reported`() {
        // The island rim: real in the table shots (where it was chosen), a bug anywhere else. It sits
        // over the island color, which is what makes it harsh enough for the rule at all.
        val rim = image(400, 200) { _, y -> if (y == 41) 0x393B40 else 0x1E1F22 }

        assertEquals(emptyList(), rulesOf(rim, "10-shell"))
        // The same color is still a bug in a shot that never asked for it.
        val elsewhere = rulesOf(rim, "02-details-general")
        assertEquals(1, elsewhere.size, elsewhere.joinToString { it.detail })
        assertEquals("stray-line", elsewhere.first().rule)
    }

    @Test
    fun `a gradient is not a line, and a short mark is not a full width one`() {
        val gradient = image(400, 200) { _, y -> 0x260000 or (y * 3 and 0xFF) or (y * 3 shl 8) }
        assertEquals(emptyList(), rulesOf(gradient, "10-shell"))

        // A 1px bright run that stops halfway across the row is content (an underline, a glyph), not
        // a line spanning the window.
        val partial = image(400, 200) { x, y -> if (y == 41 && x < 150) 0xEEEEEE else 0x26282C }
        assertEquals(emptyList(), rulesOf(partial, "10-shell"))
    }

    @Test
    fun `a thin band sandwiched between two of its own color is not a stray line`() {
        // A row that is simply the next row's color repeated has no neighbours to stand out from.
        val banded = image(400, 200) { _, y -> if (y in 40..41) 0x303236 else 0x26282C }
        assertEquals(emptyList(), rulesOf(banded, "10-shell").filter { it.rule == "stray-line" })
    }

    @Test
    fun `a foreign surface inside a band is reported`() {
        // The signature of one region painting itself with another region's color: inside a tall band of
        // white, a wide flat block of a color no token of that surface owns. It stays under half of any
        // row so the band keeps its identity - a full-width block would be its own region, not a leak.
        val leakedPane = image(400, 200) { x, y ->
            if (x in 8..199 && y in 60..119) 0x123456 else 0xFFFFFF
        }

        val issues = rulesOf(leakedPane, "02-details-general")
        assertEquals(1, issues.size, issues.joinToString { it.detail })
        assertEquals("mixed-surface", issues.first().rule)
    }

    @Test
    fun `content is not a surface`() {
        // A progress-bar-sized block and thin text-like runs sit inside a band, but neither is a wide,
        // flat second surface, and the whitelisted selection colors never count as one.
        val selection = image(400, 200) { x, y ->
            when {
                y in 40..63 && x in 8..391 -> 0x2D5177
                y in 80..95 && x in 20..120 -> 0x373328
                else -> 0x1E1F22
            }
        }
        assertEquals(emptyList(), rulesOf(selection, "01-downloads-table"))
    }
}
