package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.blaze.theming.api.ThemePalette
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Converts a packed ARGB [Long] (as carried by the UI-agnostic [ThemePalette]) into a
 * Compose [Color]. Compose's `Color(Long)` constructor reads the low 32 bits as 0xAARRGGBB,
 * which matches how the theming module stores colours.
 */
internal fun Long.toColor(): Color = Color(this)

/**
 * The palette resolved for the current composition (built-in default overlaid by the active
 * theme). Kept as the raw, framework-agnostic [ThemePalette] so both the Jewel mapping (see
 * [BlazeTheme]) and the semantic [BlazeColors] accessors read from a single source.
 */
internal val LocalThemePalette = staticCompositionLocalOf { ThemePalette.Empty }

/**
 * App-level semantic colours, sourced from the active [ThemePalette] with graceful fallbacks
 * to the stock Int UI values. Every accessor is `@Composable`, so a component simply reads
 * `BlazeColors.accent` and automatically re-composes when the theme changes.
 */
internal object BlazeColors {
    private val palette: ThemePalette
        @Composable @ReadOnlyComposable get() = LocalThemePalette.current

    val accent: Color
        @Composable @ReadOnlyComposable
        get() = palette.accentArgb?.toColor()
            ?: if (JewelTheme.isDark) Color(0xFF548AF7) else Color(0xFF3574F0)

    val success: Color
        @Composable @ReadOnlyComposable
        get() = palette.successArgb?.toColor()
            ?: if (JewelTheme.isDark) Color(0xFF5FB865) else Color(0xFF208A3C)

    val warning: Color
        @Composable @ReadOnlyComposable
        get() = palette.warningArgb?.toColor()
            ?: if (JewelTheme.isDark) Color(0xFFF2C55C) else Color(0xFFA46704)

    val error: Color
        @Composable @ReadOnlyComposable
        get() = palette.errorArgb?.toColor() ?: JewelTheme.globalColors.text.error

    /** Subtle row hover: a translucent overlay of the text colour works in both themes. */
    val hover: Color
        @Composable @ReadOnlyComposable
        get() = palette.hoverArgb?.toColor()
            ?: JewelTheme.globalColors.text.normal.copy(alpha = 0.07f)
}
