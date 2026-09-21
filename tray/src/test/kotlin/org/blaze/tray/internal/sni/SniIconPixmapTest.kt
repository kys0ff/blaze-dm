package org.blaze.tray.internal.sni

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SniIconPixmapTest {

    @Test
    fun `encodes one struct with width height and 4 bytes per pixel`() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)

        val structs = SniIconPixmap.encode(image)

        assertEquals(1, structs.size)
        val (width, height, data) = structs[0]
        assertEquals(3, width)
        assertEquals(2, height)
        assertEquals(3 * 2 * 4, (data as Array<*>).size)
    }

    @Test
    fun `pixels are big-endian argb row-major`() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x11223344.toInt())
        image.setRGB(1, 0, 0xAABBCCDD.toInt())

        val bytes = SniIconPixmap.argbBytes(image).map { it.toInt() and 0xFF }

        assertContentEquals(
            listOf(0x11, 0x22, 0x33, 0x44, 0xAA, 0xBB, 0xCC, 0xDD),
            bytes,
        )
    }
}
