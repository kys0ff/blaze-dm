package org.blaze.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LoadedResolver
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.skia.Image as SkiaImage

/**
 * Renders the icon supplied by a link-handler extension. The bytes are pulled through
 * [LinkResolverRegistry.loadIconBytes] (which reads them from the resolver's own class
 * loader, so plugin jars can ship their icon alongside their classes). Falls back to a
 * generic "unknown file" IDE icon when the extension doesn't provide one, the resource is
 * missing, or decoding fails — the resolver is never blamed for a broken asset.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ExtensionIcon(
    handler: LoadedResolver,
    registry: LinkResolverRegistry,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    val bytes = remember(handler) { registry.loadIconBytes(handler) }
    val bitmap: ImageBitmap? = remember(bytes) {
        bytes?.let {
            runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull()
        }
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
