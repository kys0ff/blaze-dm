package org.blaze.tray.internal.sni

import java.awt.image.BufferedImage

/**
 * Encodes the tray icon into the SNI `IconPixmap` wire format: an `a(iiay)` array
 * of (width, height, ARGB32 pixels) structs, each scanline packed big-endian
 * (network byte order) as required by the StatusNotifierItem spec.
 */
internal object SniIconPixmap {

    /** One (width, height, data) struct entry for the given image. */
    fun encode(image: BufferedImage): List<Array<Any>> =
        listOf(arrayOf<Any>(image.width, image.height, argbBytes(image)))

    /** ARGB pixels of [image], row-major, each pixel as 4 big-endian bytes. */
    fun argbBytes(image: BufferedImage): Array<Byte> {
        val bytes = ArrayList<Byte>(image.width * image.height * 4)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                // getRGB returns ARGB in a native-order int; the wire format is BE.
                val argb = image.getRGB(x, y)
                bytes.add(((argb ushr 24) and 0xFF).toByte())
                bytes.add(((argb ushr 16) and 0xFF).toByte())
                bytes.add(((argb ushr 8) and 0xFF).toByte())
                bytes.add((argb and 0xFF).toByte())
            }
        }
        return bytes.toTypedArray()
    }
}
