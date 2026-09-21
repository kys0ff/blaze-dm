package org.blaze.platform.tray

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Renders the Blaze flame programmatically for the tray icon — no binary asset has to
 * be shipped, and the same shape scales to any tray size (16px classic trays, 22px
 * Windows, 32px HiDPI).
 */
object BlazeTrayIcon {

    fun create(size: Int = 16): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            drawFlame(g, size.toFloat())
        } finally {
            g.dispose()
        }
        return image
    }

    private fun drawFlame(g: Graphics2D, s: Float) {
        // Outer flame: deep orange teardrop with a flicked tip.
        g.color = Color(0xFF, 0x64, 0x14)
        g.fill(flamePath(s, scale = 1f, tipLean = 0.18f))
        // Inner flame: lighter amber, sits lower in the teardrop.
        g.color = Color(0xFF, 0xA7, 0x26)
        g.fill(flamePath(s, scale = 0.55f, tipLean = 0.10f))
    }

    private fun flamePath(s: Float, scale: Float, tipLean: Float): Path2D {
        val cx = s / 2f
        val bottom = s - s * 0.06f
        val top = s * (0.06f + (1f - scale) * 0.25f)
        val width = s * 0.42f * scale
        val path = Path2D.Float()
        // Flicked tip, right flank, rounded base, left flank back to the tip.
        path.moveTo(cx + s * tipLean * scale, top)
        path.curveTo(
            cx + width * 0.4f, top + s * 0.25f * scale,
            cx + width, bottom - s * 0.45f,
            cx + width * 0.6f, bottom - s * 0.1f
        )
        path.curveTo(
            cx + width * 0.3f, bottom,
            cx - width * 0.3f, bottom,
            cx - width * 0.6f, bottom - s * 0.1f
        )
        path.curveTo(
            cx - width, bottom - s * 0.45f,
            cx - width * 0.5f, top + s * 0.4f,
            cx + s * tipLean * scale, top
        )
        path.closePath()
        return path
    }
}
