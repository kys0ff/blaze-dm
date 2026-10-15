package org.island.theme.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import org.island.foundation.CurrentIslandPlatform
import org.island.foundation.IslandPlatform
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.macOs
import org.jetbrains.jewel.intui.standalone.styling.windowsAndLinux
import org.jetbrains.jewel.ui.component.styling.*
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility.WhenScrolling
import org.jetbrains.jewel.ui.theme.scrollbarStyle
import org.jetbrains.jewel.ui.theme.textFieldStyle

/**
 * The chrome's two style overrides — the pieces of the reference the stock Jewel styling does not
 * ship: an overlay scrollbar, and a search field that belongs to its island instead of floating on
 * a bright plate of its own.
 *
 * Both are built by *re-basing* the live theme's style (its colors and metrics are kept, only the
 * offending properties are replaced), so a theme that ships its own scrollbar/field palette still
 * wins everywhere except the two traits being corrected here. Neither class exposes `copy`, so the
 * public constructors are used with the theme's own values passed back in.
 */

/**
 * The IDE's overlay scrollbar: hidden until the pointer is over the scroll area, or it is scrolling,
 * [thin][IslandLayout.OverlayScrollbarThickness], fully rounded, and fading out again after a linger.
 *
 * Jewel's Windows/Linux default is `AlwaysVisible.windowsAndLinux()` — a permanently visible,
 * square-cornered track — which is the thick `#494b4e` bar in the screenshots. `WhenScrolling` is the
 * overlay behavior macOS already gets, so it is what every platform is switched to here, with the
 * thumb rounded (`CornerSize(100)`, a pill) since the Windows/Linux metrics default to square.
 *
 * macOS keeps its own factory defaults: its overlay track is wider and its linger is longer, and the
 * reference screenshots are the Windows/Linux chrome.
 */
@Composable
fun islandOverlayScrollbarStyle(): ScrollbarStyle {
    val base = JewelTheme.scrollbarStyle
    val thickness = IslandLayout.OverlayScrollbarThickness
    val visibility = when (CurrentIslandPlatform) {
        IslandPlatform.MacOS -> WhenScrolling.macOs()
        else -> WhenScrolling.windowsAndLinux(
            trackThickness = thickness,
            trackThicknessExpanded = thickness,
            trackPadding = PaddingValues(end = 2.dp),
            trackPaddingWithBorder = PaddingValues(end = 1.dp),
        )
    }
    return remember(base, visibility) {
        ScrollbarStyle(
            colors = base.colors,
            metrics = ScrollbarMetrics(
                thumbCornerSize = CornerSize(100),
                minThumbLength = base.metrics.minThumbLength,
            ),
            trackClickBehavior = base.trackClickBehavior,
            scrollbarVisibility = visibility,
        )
    }
}

/**
 * The toolbar search field, restyled to sit *inside* its island the way the IDE's does.
 *
 * The stock dark field paints a `#505358` plate behind a `#3c3f43` border, which reads as a second,
 * much brighter surface dropped on the tool window. Here the fill is the island color itself (only
 * lifted on hover), the resting rim is the island hairline, and the accent appears on the rim when the
 * field takes focus — the IDE's single focus cue. The theme's content padding, corner and minimum size
 * are reused, and the border is pinned to [IslandLayout.IslandBorderWidth] (1dp).
 */
@Composable
fun islandSearchFieldStyle(): TextFieldStyle {
    val base = JewelTheme.textFieldStyle
    val island = IslandChromeColors.island
    val raised = IslandChromeColors.stripeSelected
    val rim = IslandChromeColors.islandBorder
    val accent = IslandSemanticColors.accent
    val text = JewelTheme.globalColors.text
    val borders = JewelTheme.globalColors.borders
    return remember(base, island, raised, rim, accent) {
        TextFieldStyle(
            colors = TextFieldColors(
                background = island,
                backgroundDisabled = island,
                backgroundFocused = island,
                backgroundPressed = island,
                backgroundHovered = raised,
                content = text.normal,
                contentDisabled = text.disabled,
                contentFocused = text.normal,
                contentPressed = text.normal,
                contentHovered = text.normal,
                border = rim,
                borderDisabled = borders.disabled,
                borderFocused = accent,
                borderPressed = accent,
                borderHovered = rim,
                caret = text.normal,
                caretDisabled = text.disabled,
                caretFocused = text.normal,
                caretPressed = text.normal,
                caretHovered = text.normal,
                placeholder = text.info,
            ),
            metrics = TextFieldMetrics(
                borderWidth = IslandLayout.IslandBorderWidth,
                contentPadding = base.metrics.contentPadding,
                cornerSize = base.metrics.cornerSize,
                minSize = base.metrics.minSize,
            ),
            iconButtonStyle = base.iconButtonStyle,
        )
    }
}
