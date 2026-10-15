package org.blaze.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.resolver.api.IconFormat
import org.blaze.resolver.api.ResolverIcon
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LoadedResolver
import org.island.ui.icon.IslandExtensionIcon
import org.island.ui.icon.IslandIconFormat
import org.island.ui.icon.IslandIconSource
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Blaze's extension-icon adapter over the generic [IslandExtensionIcon]: maps a link-handler
 * extension's [ResolverIcon] (or a loaded resolver pulled through [LinkResolverRegistry.loadIcon],
 * which prefers the dark variant when [darkMode] — defaulting to the live Jewel theme — is set) onto
 * the island's raw-bytes icon source and lets the shared decoder do the rest. The fallback picture
 * (generic IDE glyph when the asset is missing or broken) lives in the island component.
 */
@Composable
fun ExtensionIcon(
    icon: ResolverIcon?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val contentDescription = blazeStrings.common.extensionIcon
    IslandExtensionIcon(
        icon = icon?.toIslandSource(),
        modifier = modifier,
        size = size,
        contentDescription = contentDescription,
    )
}

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

/** Maps Blaze's plugin icon descriptor onto the island's format-tagged byte source. */
fun ResolverIcon.toIslandSource(): IslandIconSource = IslandIconSource(
    bytes = bytes,
    format = when (format) {
        IconFormat.PNG -> IslandIconFormat.PNG
        IconFormat.SVG -> IslandIconFormat.SVG
    },
)
