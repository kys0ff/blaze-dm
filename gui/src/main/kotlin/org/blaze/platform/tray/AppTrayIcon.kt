package org.blaze.platform.tray

import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Blaze's tray artwork: the application icon (`/app-icon.png` from this module's
 * resources) scaled on demand to whatever pixel size the tray backend asks for.
 * Living in the app module keeps the shared `:tray` library free of Blaze assets.
 */
object AppTrayIcon {

    private const val RESOURCE = "/app-icon.png"

    private val logger = LoggerFactory.getLogger(AppTrayIcon::class.java)

    private val source: BufferedImage? by lazy { load() }

    /** The app icon scaled to [size]x[size] pixels; transparent when the asset is missing. */
    fun image(size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val icon = source ?: return result
        val g = result.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(icon, 0, 0, size, size, null)
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
}
