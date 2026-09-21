package org.blaze.presentation.theme

import androidx.compose.ui.graphics.Color
import org.blaze.theming.api.ThemePalette
import org.jetbrains.jewel.foundation.BorderColors
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.OutlineColors
import org.jetbrains.jewel.foundation.TextColors
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.light

/**
 * Bridges the UI-agnostic [ThemePalette] into Jewel's [GlobalColors], so a theme can restyle
 * the surfaces Jewel itself paints (panel background, borders, secondary text) and — crucially
 * — the focus colour that every input, checkbox and button uses for its focused outline.
 *
 * Only the colours a theme actually provides are overridden; everything else keeps the stock
 * Int UI value by delegating to the framework's own builders. When a theme overrides nothing,
 * the untouched default [GlobalColors] is returned.
 */
internal fun buildJewelGlobalColors(isDark: Boolean, palette: ThemePalette): GlobalColors {
    val accent: Color? = palette.accentArgb?.toColor()
    val panel: Color? = palette.panelBackgroundArgb?.toColor()
    val border: Color? = palette.borderArgb?.toColor()
    val info: Color? = palette.infoTextArgb?.toColor()

    if (accent == null && panel == null && border == null && info == null) {
        return if (isDark) GlobalColors.dark() else GlobalColors.light()
    }

    return if (isDark) {
        val defaultBorders = BorderColors.dark()
        val defaultOutlines = OutlineColors.dark()
        GlobalColors.dark(
            borders = BorderColors.dark(
                normal = border ?: defaultBorders.normal,
                focused = accent ?: defaultBorders.focused,
                disabled = defaultBorders.disabled,
            ),
            outlines = OutlineColors.dark(
                focused = accent ?: defaultOutlines.focused,
                focusedWarning = defaultOutlines.focusedWarning,
                focusedError = defaultOutlines.focusedError,
                warning = defaultOutlines.warning,
                error = defaultOutlines.error,
            ),
            text = if (info != null) TextColors.dark(info = info) else TextColors.dark(),
            panelBackground = panel ?: GlobalColors.dark().panelBackground,
        )
    } else {
        val defaultBorders = BorderColors.light()
        val defaultOutlines = OutlineColors.light()
        GlobalColors.light(
            borders = BorderColors.light(
                normal = border ?: defaultBorders.normal,
                focused = accent ?: defaultBorders.focused,
                disabled = defaultBorders.disabled,
            ),
            outlines = OutlineColors.light(
                focused = accent ?: defaultOutlines.focused,
                focusedWarning = defaultOutlines.focusedWarning,
                focusedError = defaultOutlines.focusedError,
                warning = defaultOutlines.warning,
                error = defaultOutlines.error,
            ),
            text = if (info != null) TextColors.light(info = info) else TextColors.light(),
            panelBackground = panel ?: GlobalColors.light().panelBackground,
        )
    }
}
