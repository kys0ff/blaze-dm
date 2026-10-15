package org.island.theme.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccentSelectorTest {

    // Accepts the high-bit-set hex literals as Longs (Kotlin's default for 0xFF...) and packs
    // them into the Int ARGB the model uses.
    private fun color(argb: Long, count: Int, total: Int = 1000) =
        DominantColor(argb = argb.toInt(), count = count, percentage = count.toFloat() / total)

    @Test
    fun `a vivid minority color beats a dominant gray`() {
        // Typical emblem: mostly dark shell, a small bright flame.
        val shell = color(0xFF2B2B2B, 700)
        val flame = color(0xFFFF6A1A, 120)
        val picked = AccentSelector.select(listOf(shell, flame))

        assertEquals(0xFFFF6A1A.toInt(), picked?.argb)
    }

    @Test
    fun `the most vivid of several brand colors wins`() {
        val muted = color(0xFF7A8BA0, 400) // blue-gray: saturation ~0.24, under the 0.30 bar
        val teal = color(0xFF00BFA5, 200)
        val picked = AccentSelector.select(listOf(muted, teal))

        assertEquals(0xFF00BFA5.toInt(), picked?.argb)
    }

    @Test
    fun `achromatic artwork yields no accent at all`() {
        assertNull(AccentSelector.select(listOf(color(0xFF808080, 600), color(0xFFC0C0C0, 400))))
    }

    @Test
    fun `near-black and near-white are rejected even when colorful`() {
        val soDark = color(0xFF1A0800, 500) // deep brown, brightness < 0.35
        val soLight = color(0xFFFFF5E6, 500) // cream, saturation < 0.30
        assertNull(AccentSelector.select(listOf(soDark, soLight)))
    }

    @Test
    fun `an empty palette has nothing to pick`() {
        assertNull(AccentSelector.select(emptyList()))
        assertNull(AccentSelector.selectArgb(emptyList()))
    }

    @Test
    fun `selectArgb mirrors select`() {
        val flame = color(0xFFFF6A1A, 300)
        assertEquals(flame.argb, AccentSelector.selectArgb(listOf(color(0xFF2B2B2B, 700), flame)))
    }
}
