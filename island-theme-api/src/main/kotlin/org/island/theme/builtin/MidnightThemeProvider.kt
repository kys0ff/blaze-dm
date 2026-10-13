package org.island.theme.builtin

import org.island.theme.api.*

/**
 * A complete, cool-toned theme shipped as a built-in so the picker demonstrates that a theme can
 * restyle more than the accent: it also retints the island surfaces, the Islands chrome (frame,
 * stripe states, rim) and the secondary text. Unlike [TealThemeProvider], which only changes the
 * accent, this theme is noticeably different across the whole window at a glance.
 *
 * Every token it paints is also published as a user-editable option, so the slate can be nudged
 * warmer without a fork — the app applies the answers onto the resolved palette.
 */
class MidnightThemeProvider : ConfigurableIslandThemeProvider {
    override val id: String = "midnight"
    override val displayName: String = "Midnight"
    override val description: String = "A cool blue accent over deep slate surfaces"
    override val author: String = "Island"
    override val version: String = "2.0.0"

    override val optionDescriptors: List<ThemeOption> = listOf(
        themeColorOption(
            "accent", "Accent colour", ThemeColorTarget.ACCENT,
            defaultArgb = DARK_ACCENT, defaultLightArgb = LIGHT_ACCENT,
        ),
        themeColorOption(
            "error", "Error colour", ThemeColorTarget.ERROR,
            defaultArgb = DARK_ERROR, defaultLightArgb = LIGHT_ERROR,
        ),
        themeColorOption(
            "island", "Island surface", ThemeColorTarget.ISLANDS_SURFACE,
            description = "The panel body every download row and tool window sits on.",
            defaultArgb = DARK_ISLAND, defaultLightArgb = LIGHT_ISLAND,
        ),
        themeColorOption(
            "frame", "Window frame", ThemeColorTarget.ISLANDS_FRAME,
            description = "Title bar, tool-window stripe and the gaps between islands.",
            defaultArgb = DARK_FRAME, defaultLightArgb = LIGHT_FRAME,
        ),
        themeColorOption(
            "border", "Island rim", ThemeColorTarget.ISLANDS_BORDER,
            defaultArgb = DARK_BORDER, defaultLightArgb = LIGHT_BORDER,
        ),
        themeAmbientIntensityOption("glow", "Ambient glow strength", defaultPercent = 18, defaultLightPercent = 10),
    )

    override fun provide(dark: Boolean): ThemePalette = if (dark) {
        ThemePalette(
            accentArgb = DARK_ACCENT,
            successArgb = 0xFF5FB865,
            warningArgb = 0xFFE6B450,
            errorArgb = DARK_ERROR,
            panelBackgroundArgb = DARK_ISLAND,
            borderArgb = DARK_BORDER,
            infoTextArgb = 0xFF8A93A0,
            islands = IslandsPaletteOf(DARK_FRAME, DARK_ISLAND, DARK_BORDER, IslandsDefaults.STRIPE_HOVER_DARK),
        )
    } else {
        ThemePalette(
            accentArgb = LIGHT_ACCENT,
            successArgb = 0xFF208A3C,
            warningArgb = 0xFFB8791E,
            errorArgb = LIGHT_ERROR,
            panelBackgroundArgb = LIGHT_ISLAND,
            borderArgb = LIGHT_BORDER,
            infoTextArgb = 0xFF5A6472,
            islands = IslandsPaletteOf(LIGHT_FRAME, LIGHT_ISLAND, LIGHT_BORDER, IslandsDefaults.STRIPE_HOVER_LIGHT),
        )
    }

    private companion object {
        const val DARK_ACCENT = 0xFF5B9DFFL
        const val DARK_ERROR = 0xFFE06C75L
        const val DARK_ISLAND = 0xFF262B33L
        const val DARK_FRAME = 0xFF1B1F26L
        const val DARK_BORDER = 0xFF3A424FL

        // Day-mode counterparts, so an untouched option resolves to the light palette instead of
        // stretching the dark token over light chrome.
        const val LIGHT_ACCENT = 0xFF2E6FD8L
        const val LIGHT_ERROR = 0xFFC0392BL
        const val LIGHT_ISLAND = 0xFFF1F4F9L
        const val LIGHT_FRAME = 0xFFEDF0F6L
        const val LIGHT_BORDER = 0xFFD3DAE4L
    }
}

/** Builds the Islands chrome override for a theme that restyles the shell's neutrals. */
private fun IslandsPaletteOf(
    frame: Long,
    surface: Long,
    border: Long,
    stripeHover: Long,
) = IslandsPalette(
    frameArgb = frame,
    surfaceArgb = surface,
    borderArgb = border,
    // The stripe's selected state reads as a lift over the frame, so it follows it.
    stripeSelectedArgb = frame blendWith border,
    stripeHoverArgb = stripeHover,
)

/** A 50/50 mix used to keep a derived token between two declared ones. */
private infix fun Long.blendWith(other: Long): Long {
    fun channel(value: Long, shift: Int, mask: Long) = (value shr shift) and mask
    fun mix(a: Long, b: Long, shift: Int, mask: Long): Long {
        val merged = (channel(a, shift, mask) + channel(b, shift, mask)) / 2
        return merged shl shift
    }
    return mix(this, other, 24, 0xFFL) or
            mix(this, other, 16, 0xFFL) or
            mix(this, other, 8, 0xFFL) or
            mix(this, other, 0, 0xFFL)
}
