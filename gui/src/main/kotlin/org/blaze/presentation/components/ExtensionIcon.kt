package org.blaze.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import org.blaze.i18n.blazeStrings
import org.blaze.resolver.api.IconFormat
import org.blaze.resolver.api.ResolverIcon
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LoadedResolver
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.Image as SkiaImage

/**
 * Renders the icon supplied by a link-handler extension from a loaded [ResolverIcon]. Falls
 * back to a generic "unknown file" IDE icon when the icon is null or decoding fails — the
 * resolver is never blamed for a broken asset. PNG bytes go straight to Skia; SVG bytes are
 * rasterized with JSVG (2× supersampled) and re-encoded as PNG for the same decode path.
 * This overload lets a screen model preload the icon and hand the pane a plain value, so
 * the composable need not resolve the registry itself.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ExtensionIcon(
    icon: ResolverIcon?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val density = LocalDensity.current
    val bitmap: ImageBitmap? = remember(icon, size, density) {
        icon?.let { runCatching { decodeResolverIcon(it, with(density) { size.roundToPx() }) }.getOrNull() }
    }

    val contentDescription = blazeStrings.common.extensionIcon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier.size(size)
        )
    } else {
        Box(modifier = modifier.size(size)) {
            Icon(
                key = AllIconsKeys.FileTypes.Any_type,
                contentDescription = contentDescription,
                modifier = Modifier.size(size)
            )
        }
    }
}

/**
 * Renders the icon supplied by a link-handler extension. The asset is pulled through
 * [LinkResolverRegistry.loadIcon], which reads the manifest-declared resource from the
 * resolver's own class loader and prefers the dark variant when [darkMode] (defaulting to
 * the live Jewel theme) is set. Falls back to a generic placeholder when the extension
 * declares no icon, the resource is missing, or decoding fails.
 */
@Composable
fun ExtensionIcon(
    handler: LoadedResolver,
    registry: LinkResolverRegistry,
    modifier: Modifier = Modifier,
    darkMode: Boolean = JewelTheme.isDark,
    size: Dp = 16.dp,
) {
    val icon = remember(handler, darkMode) { registry.loadIcon(handler, darkMode) }
    ExtensionIcon(icon = icon, modifier = modifier, size = size)
}

/** Decodes the icon's bytes to a Compose bitmap, rasterizing SVGs at [targetPx] (supersampled). */
internal fun decodeResolverIcon(icon: ResolverIcon, targetPx: Int): ImageBitmap? {
    val encoded = when (icon.format) {
        IconFormat.PNG -> icon.bytes
        IconFormat.SVG -> rasterizeSvg(icon.bytes, targetPx * 2) ?: return null
    }
    return runCatching { SkiaImage.makeFromEncoded(encoded).toComposeImageBitmap() }.getOrNull()
}

/**
 * SVG → PNG bytes through JSVG: the document is rendered into a [targetPx]-square
 * [BufferedImage] (viewBox scaled to fit) and re-encoded, so every downstream decode stays
 * on the one Skia path. Null when the document can't be parsed (JSVG *throws* on malformed
 * XML rather than returning null, so the whole pass is failure-tolerant). Internal for a
 * headless rasterizer smoke test; the UI only ever reaches it through the composables above.
 */
internal fun rasterizeSvg(bytes: ByteArray, targetPx: Int): ByteArray? = runCatching {
    val document: SVGDocument =
        SVGLoader().load(ByteArrayInputStream(bytes), null, LoaderContext.createDefault()) ?: return null
    val image = BufferedImage(targetPx, targetPx, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        document.render(null, graphics, ViewBox(0f, 0f, targetPx.toFloat(), targetPx.toFloat()))
    } finally {
        graphics.dispose()
    }
    val out = ByteArrayOutputStream()
    if (ImageIO.write(image, "png", out)) out.toByteArray() else null
}.getOrNull()
