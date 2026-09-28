package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.window.defaultTitleBarStyle

/**
 * JetBrains-IDE-style "ambient" glow for the title bar: Jewel's `TitleBar` paints a horizontal
 * gradient whose *center* is the color returned here (background at both ends, brightest left
 * of center - the exact geometry IntelliJ uses, -100dp..400dp). Blending the effective accent of
 * the active theme into the title-bar background reproduces the look: IDEA's stock blue ambient
 * on the default palette, and a matching tint when a theme carries its own accent.
 *
 * An inactive window gets [Color.Unspecified] - IntelliJ flattens the bar to the solid inactive
 * background, and Jewel's gradient machinery skips blending for unspecified colors.
 */
@Composable
@ReadOnlyComposable
internal fun ambientTitleBarGradientColor(isActive: Boolean): Color {
    if (!isActive) return Color.Unspecified
    // Same style lookup the Jewel title bar itself renders with, so the endpoints of the
    // gradient and this center tint can never drift apart.
    val style = JewelTheme.defaultTitleBarStyle
    return ambientTitleBarCenterColor(style.colors.background, BlazeColors.accent, JewelTheme.isDark)
}

/**
 * Pure color math, kept out of the composition so it can be tested directly. Blended per
 * component (the [Color] components already round-trip through Compose's working color space),
 * so the center can never leave the straight line between background and tint.
 */
internal fun ambientTitleBarCenterColor(base: Color, tint: Color, isDark: Boolean): Color {
    val fraction = if (isDark) DARK_AMBIENT_FRACTION else LIGHT_AMBIENT_FRACTION
    return Color(
        red = base.red + (tint.red - base.red) * fraction,
        green = base.green + (tint.green - base.green) * fraction,
        blue = base.blue + (tint.blue - base.blue) * fraction,
        alpha = base.alpha,
    )
}

/** Subtle on purpose: light bars barely leave their neutral, dark ones gain a steel cast. */
private const val LIGHT_AMBIENT_FRACTION = 0.17f
private const val DARK_AMBIENT_FRACTION = 0.26f
