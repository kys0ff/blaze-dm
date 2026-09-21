package org.blaze.theming.builtin

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette

/**
 * A complete, cool-toned theme shipped as a built-in so the picker demonstrates that a theme can
 * restyle more than the accent: it also tints the panel surfaces (sidebar, headers, dialogs),
 * the borders and the secondary text. Unlike [TealThemeProvider], which only changes the accent,
 * this theme is noticeably different across the whole window at a glance.
 */
class MidnightThemeProvider : BlazeThemeProvider {
    override val id: String = "midnight"
    override val displayName: String = "Midnight"
    override val description: String = "A cool blue accent over deep slate surfaces"

    override fun provide(dark: Boolean): ThemePalette = if (dark) {
        ThemePalette(
            accentArgb = 0xFF5B9DFF,
            successArgb = 0xFF5FB865,
            warningArgb = 0xFFE6B450,
            errorArgb = 0xFFE06C75,
            panelBackgroundArgb = 0xFF262B33,
            borderArgb = 0xFF3A424F,
            infoTextArgb = 0xFF8A93A0,
        )
    } else {
        ThemePalette(
            accentArgb = 0xFF2E6FD8,
            successArgb = 0xFF208A3C,
            warningArgb = 0xFFB8791E,
            errorArgb = 0xFFC0392B,
            panelBackgroundArgb = 0xFFF1F4F9,
            borderArgb = 0xFFD3DAE4,
            infoTextArgb = 0xFF5A6472,
        )
    }
}
