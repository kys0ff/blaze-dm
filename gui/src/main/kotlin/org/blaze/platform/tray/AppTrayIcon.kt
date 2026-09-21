package org.blaze.platform.tray

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Blaze's tray artwork: the application icon (`/app-icon.png` from this module's
 * resources) scaled on demand to whatever pixel size the tray backend asks for.
 * If the asset is missing or unreadable, the Blaze flame is drawn with Java2D instead,
 * so the tray never ends up blank.
 * Living in the app module keeps the shared `:tray` library free of Blaze assets.
 */
object AppTrayIcon {

    private const val RESOURCE = "/app-icon.png"

    private val logger = LoggerFactory.getLogger(AppTrayIcon::class.java)

    private val source: BufferedImage? by lazy { load() }

    /** The app icon scaled to [size]x[size] pixels; the drawn flame when the asset is missing. */
    fun image(size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val icon = source
            if (icon != null) {
                g.drawImage(icon, 0, 0, size, size, null)
            } else {
                drawFlame(g, size)
            }
        } finally {
            g.dispose()
        }
        return result
    }

    private fun load(): BufferedImage? =
        runCatching {
            AppTrayIcon::class.java.getResourceAsStream(RESOURCE)?.use(ImageIO::read)
        }.onFailure { logger.warn("Could not load the tray icon asset $RESOURCE", it) }
            .onSuccess { if (it == null) logger.warn("Tray icon asset $RESOURCE not found") }
            .getOrNull()

    // ---- Fallback: the Blaze flame, drawn on a 128x128 design grid --------------------------

    private const val GRID = 128.0

    /** Flame body (with the download arrow cut out) plus the two facet overlays. */
    private class FlameArt(val body: Area, val lightFacet: Area, val darkFacet: Area)

    private val flameArt: FlameArt by lazy {
        val flame = Path2D.Double(Path2D.WIND_NON_ZERO).apply {
            moveTo(64.0, 114.0)
            curveTo(86.0, 114.0, 100.0, 98.0, 100.0, 78.0)
            curveTo(100.0, 58.0, 84.0, 44.0, 72.0, 12.0)
            curveTo(62.0, 30.0, 54.0, 44.0, 56.0, 58.0)
            curveTo(48.0, 56.0, 42.0, 50.0, 36.0, 38.0)
            curveTo(30.0, 56.0, 28.0, 66.0, 28.0, 78.0)
            curveTo(28.0, 98.0, 44.0, 114.0, 64.0, 114.0)
            closePath()
        }
        val arrow = Path2D.Double().apply {
            moveTo(57.0, 62.0)
            lineTo(71.0, 62.0)
            lineTo(71.0, 82.0)
            lineTo(84.0, 82.0)
            lineTo(64.0, 105.0)
            lineTo(44.0, 82.0)
            lineTo(57.0, 82.0)
            closePath()
        }
        val lightFacet = Path2D.Double().apply {
            moveTo(72.0, 12.0)
            lineTo(112.0, 92.0)
            lineTo(64.0, 122.0)
            lineTo(68.0, 70.0)
            closePath()
        }
        val darkFacet = Path2D.Double().apply {
            moveTo(18.0, 70.0)
            lineTo(64.0, 120.0)
            lineTo(18.0, 120.0)
            closePath()
        }

        val body = Area(flame).apply { subtract(Area(arrow)) }
        // Intersect the facets with the body geometrically (rather than using a clip)
        // so their edges stay anti-aliased at tiny tray sizes.
        FlameArt(
            body = body,
            lightFacet = Area(lightFacet).apply { intersect(body) },
            darkFacet = Area(darkFacet).apply { intersect(body) },
        )
    }

    private fun drawFlame(g: Graphics2D, size: Int) {
        val art = flameArt

        // Same placement as the SVG: scale to the grid, then inset the flame slightly.
        val scale = size / GRID
        g.scale(scale, scale)
        g.translate(5.12, 5.12)
        g.scale(0.92, 0.92)

        g.paint = LinearGradientPaint(
            Point2D.Float(64f, 114f), // hot core at the bottom
            Point2D.Float(64f, 12f), // cool tip at the top
            floatArrayOf(0f, 0.30f, 0.66f, 1f),
            arrayOf(
                Color(0xFCF84A),
                Color(0xF97A12),
                Color(0xFE315D),
                Color(0xB345F1),
            ),
        )
        g.fill(art.body)

        g.paint = Color(1f, 1f, 1f, 0.14f)
        g.fill(art.lightFacet)
        g.paint = Color(0f, 0f, 0f, 0.12f)
        g.fill(art.darkFacet)
    }
}