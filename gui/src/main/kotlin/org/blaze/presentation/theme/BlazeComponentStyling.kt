package org.blaze.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import org.blaze.theming.api.ThemePalette
import org.jetbrains.jewel.intui.standalone.styling.Default
import org.jetbrains.jewel.intui.standalone.styling.dark
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.light
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.ButtonColors
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.HorizontalProgressBarColors
import org.jetbrains.jewel.ui.component.styling.HorizontalProgressBarStyle
import org.jetbrains.jewel.ui.component.styling.SimpleListItemColors
import org.jetbrains.jewel.ui.component.styling.SimpleListItemStyle

/**
 * Rebuilds the framework's [ComponentStyling] so the widgets Jewel paints with a *baked* accent
 * — the primary (default) button, the horizontal progress bar, and the active list-item selection
 * — follow the accent color supplied by the current [ThemePalette].
 *
 * Jewel constructs these styles from the fixed Int UI palette, so overriding [org.jetbrains.jewel.foundation.GlobalColors]
 * alone is not enough to re-theme them; the styles themselves have to be rebuilt. Every other
 * component keeps its stock styling. Returns `null` when the palette provides no accent, letting
 * the caller fall back to [ComponentStyling.default].
 */
internal fun buildAccentStyling(palette: ThemePalette, isDark: Boolean): ComponentStyling? {
    val accent = palette.accentArgb?.toColor() ?: return null

    val hover = lerp(accent, if (isDark) Color.White else Color.Black, 0.14f)
    val pressed = lerp(accent, Color.Black, 0.20f)
    val selection = accent.copy(alpha = if (isDark) 0.42f else 0.30f)

    val buttonColors = ButtonColors.Default.run {
        if (isDark) {
            dark(
                background = SolidColor(accent),
                backgroundFocused = SolidColor(accent),
                backgroundHovered = SolidColor(hover),
                backgroundPressed = SolidColor(pressed),
                border = SolidColor(accent),
            )
        } else {
            light(
                background = SolidColor(accent),
                backgroundFocused = SolidColor(accent),
                backgroundHovered = SolidColor(hover),
                backgroundPressed = SolidColor(pressed),
                border = SolidColor(accent),
            )
        }
    }

    val progressColors = if (isDark) {
        HorizontalProgressBarColors.dark(
            progress = accent,
            indeterminateBase = accent.copy(alpha = 0.45f),
            indeterminateHighlight = accent,
        )
    } else {
        HorizontalProgressBarColors.light(
            progress = accent,
            indeterminateBase = accent.copy(alpha = 0.45f),
            indeterminateHighlight = accent,
        )
    }

    val listColors = if (isDark) {
        SimpleListItemColors.dark(backgroundSelectedActive = selection)
    } else {
        SimpleListItemColors.light(backgroundSelectedActive = selection)
    }

    val defaultButtonStyle = if (isDark) {
        ButtonStyle.Default.dark(colors = buttonColors)
    } else {
        ButtonStyle.Default.light(colors = buttonColors)
    }
    val progressStyle = if (isDark) {
        HorizontalProgressBarStyle.dark(colors = progressColors)
    } else {
        HorizontalProgressBarStyle.light(colors = progressColors)
    }
    val listStyle = if (isDark) {
        SimpleListItemStyle.dark(colors = listColors)
    } else {
        SimpleListItemStyle.light(colors = listColors)
    }

    return if (isDark) {
        ComponentStyling.dark(
            defaultButtonStyle = defaultButtonStyle,
            horizontalProgressBarStyle = progressStyle,
            simpleListItemStyle = listStyle,
        )
    } else {
        ComponentStyling.light(
            defaultButtonStyle = defaultButtonStyle,
            horizontalProgressBarStyle = progressStyle,
            simpleListItemStyle = listStyle,
        )
    }
}
