package org.blaze.theming.builtin

import org.blaze.theming.api.*

/**
 * A ready-to-use alternative accent, shipped as a built-in so the theme picker has more
 * than one option out of the box. It only overrides the accent and the semantic state
 * colors, leaving surfaces and text — and the Islands chrome — to fall back to the design
 * system's stock values.
 *
 * It is also *configurable*: every colour it paints is published as a user-editable option,
 * demonstrating the contract's own customization path (the app applies the answers; no code of
 * this theme runs at render time).
 */
class TealThemeProvider : ConfigurableBlazeThemeProvider {
    override val id: String = "teal"
    override val displayName: String = "Teal"
    override val description: String = "A calmer teal accent"
    override val author: String = "Blaze"
    override val version: String = "2.0.0"

    override val optionDescriptors: List<ThemeOption> = listOf(
        themeColorOption(
            "accent", "Accent colour", ThemeColorTarget.ACCENT,
            defaultArgb = DARK_ACCENT, defaultLightArgb = LIGHT_ACCENT,
        ),
        themeColorOption(
            "success", "Success colour", ThemeColorTarget.SUCCESS,
            defaultArgb = DARK_SUCCESS, defaultLightArgb = LIGHT_SUCCESS,
        ),
        themeColorOption(
            "warning", "Warning colour", ThemeColorTarget.WARNING,
            defaultArgb = DARK_WARNING, defaultLightArgb = LIGHT_WARNING,
        ),
        themeColorOption(
            "frame", "Window frame", ThemeColorTarget.ISLANDS_FRAME,
            description = "The chrome islands float on: title bar, stripes, gaps.",
            defaultArgb = IslandsDefaults.FRAME_DARK, defaultLightArgb = IslandsDefaults.FRAME_LIGHT,
        ),
        themeAmbientIntensityOption(
            "glow", "Ambient glow strength",
            description = "How far the accent bleeds into the frame at the window's corner.",
            defaultPercent = IslandsDefaults.AMBIENT_INTENSITY_DARK_PERCENT,
            defaultLightPercent = IslandsDefaults.AMBIENT_INTENSITY_LIGHT_PERCENT,
        ),
    )

    override fun provide(dark: Boolean): ThemePalette = if (dark) {
        ThemePalette(
            accentArgb = DARK_ACCENT,
            successArgb = DARK_SUCCESS,
            warningArgb = DARK_WARNING,
        )
    } else {
        ThemePalette(
            accentArgb = LIGHT_ACCENT,
            successArgb = LIGHT_SUCCESS,
            warningArgb = LIGHT_WARNING,
        )
    }

    private companion object {
        const val DARK_ACCENT = 0xFF3FB6A8L
        const val DARK_SUCCESS = 0xFF5FB865L
        const val DARK_WARNING = 0xFFF2C55CL

        // Day-mode counterparts, so an untouched option resolves to the light palette instead of
        // stretching the dark token over light chrome.
        const val LIGHT_ACCENT = 0xFF0F9D8CL
        const val LIGHT_SUCCESS = 0xFF208A3CL
        const val LIGHT_WARNING = 0xFFA46704L
    }
}
