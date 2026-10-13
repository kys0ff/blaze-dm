package org.island.ui.icon

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
import org.island.ui.text.LocalIslandUiStrings
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.Image as SkiaImage

/** The encoding of an [IslandIconSource]'s bytes. */
enum class IslandIconFormat { PNG, SVG }

/**
 * An icon asset supplied by an app extension, decoupled from whatever plugin machinery produced it:
 * raw bytes plus their format. Apps map their own descriptor types onto this.
 */
data class IslandIconSource(val bytes: ByteArray, val format: IslandIconFormat) {
    override fun equals(other: Any?): Boolean =
        other is IslandIconSource && format == other.format && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + format.hashCode()
}

/**
 * Renders an extension-supplied icon from an [IslandIconSource]. Falls back to a generic
 * "unknown file" IDE icon when the source is null or decoding fails — the extension is never
 * blamed for a broken asset. PNG bytes go straight to Skia; SVG bytes are rasterized with JSVG
 * (2x supersampled) and re-encoded as PNG for the same decode path.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun IslandExtensionIcon(
    icon: IslandIconSource?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    contentDescription: String = LocalIslandUiStrings.current.extensionIcon(),
) {
    val density = LocalDensity.current
    val bitmap: ImageBitmap? = remember(icon, size, density) {
        icon?.let { runCatching { decodeIslandIcon(it, with(density) { size.roundToPx() }) }.getOrNull() }
    }

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

/** Decodes the icon's bytes to a Compose bitmap, rasterizing SVGs at [targetPx] (supersampled). */
fun decodeIslandIcon(icon: IslandIconSource, targetPx: Int): ImageBitmap? {
    val encoded = when (icon.format) {
        IslandIconFormat.PNG -> icon.bytes
        IslandIconFormat.SVG -> rasterizeSvg(icon.bytes, targetPx * 2) ?: return null
    }
    return runCatching { SkiaImage.makeFromEncoded(encoded).toComposeImageBitmap() }.getOrNull()
}

/**
 * SVG → PNG bytes through JSVG: the document is rendered into a [targetPx]-square
 * [BufferedImage] (viewBox scaled to fit) and re-encoded, so every downstream decode stays
 * on the one Skia path. Null when the document can't be parsed (JSVG *throws* on malformed
 * XML rather than returning null, so the whole pass is failure-tolerant).
 */
fun rasterizeSvg(bytes: ByteArray, targetPx: Int): ByteArray? = runCatching {
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
