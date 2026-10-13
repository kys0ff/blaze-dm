package org.island.theme.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.island.theme.api.IslandsDefaults
import org.island.theme.api.ThemePalette
import org.island.theme.api.withFallback
import org.jetbrains.jewel.foundation.DisabledAppearanceValues
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.*
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.LocalTypography
import org.jetbrains.jewel.ui.component.styling.LocalScrollbarStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * The single theming entry point.
 *
 * Blaze renders exactly one design system — the JetBrains New UI "Islands" shell — and this
 * function is where the shell's resolved tokens and the active theme meet. [palette] is the theme
 * resolved by [org.island.theme.core.ThemeRegistry] for the current light/dark [isDark] choice;
 * it drives three layers at once:
 *  - the Islands chrome, completed from [IslandsDefaults] and exposed through [LocalIslandsPalette]
 *    (read by [IslandChromeColors], the frame layer and every island);
 *  - the Jewel surfaces, via [buildJewelGlobalColors] folded into the `ThemeDefinition`; and
 *  - the app's semantic colors, exposed through [LocalThemePalette] to [IslandSemanticColors].
 *
 * Passing an empty palette reproduces the stock Islands look, because resolution always ends by
 * filling unset tokens from the design system — a theme can never leave the chrome unpainted.
 *
 * The typography parameters carry the user's font customizations: [uiFontFamilyId] and
 * [monoFontFamilyId] index the [IslandFontFamilies] catalogue (interface and monospace families),
 * and [uiTextScalePercent] scales every sp-driven text through the density's font scale. Their
 * defaults are Jewel's own stock families at 100%, so a caller that never customizes renders
 * exactly as before.
 */
@Composable
fun IslandTheme(
    palette: ThemePalette = ThemePalette.Empty,
    isDark: Boolean = isSystemInDarkTheme(),
    uiFontFamilyId: String = IslandFontFamilies.DefaultUiFontId,
    monoFontFamilyId: String = IslandFontFamilies.DefaultMonoFontId,
    uiTextScalePercent: Int = IslandFontFamilies.DefaultTextScalePercent,
    content: @Composable () -> Unit,
) {
    // The chrome the whole window renders with: the theme's Islands overrides, completed from the
    // stock design-system values so no token can be left unset by a partial theme.
    val islands = remember(palette, isDark) {
        palette.islands.withFallback(IslandsDefaults.forMode(isDark))
    }

    // The user's font choices, resolved through the catalogue. The stock ids (Inter for the UI,
    // JetBrains Mono for the mono slot) deliberately switch nothing off: the theme then keeps
    // Jewel's own text styles and typography, so default settings stay bit-identical to plain Jewel.
    val uiFont = remember(uiFontFamilyId) { IslandFontFamilies.uiFontFor(uiFontFamilyId) }
    val monoFont = remember(monoFontFamilyId) { IslandFontFamilies.monoFontFor(monoFontFamilyId) }
    val substituteUi = uiFont.id != IslandFontFamilies.INTER
    val substituteMono = monoFont.id != IslandFontFamilies.JETBRAINS_MONO

    val themeDefinition = remember(isDark, palette, islands, uiFontFamilyId, monoFontFamilyId) {
        val globalColors = buildJewelGlobalColors(isDark, palette, islands)
        // The definition's text styles feed LocalTextStyle and JewelTheme.defaultTextStyle — the
        // two hooks every Jewel and Blaze label ultimately reads. A substituted bundled family
        // also gets its line height recomputed from its own vertical metrics (see the catalogue).
        val defaultTextStyle = if (substituteUi) {
            IslandFontFamilies.substituteInto(
                JewelTheme.createDefaultTextStyle(fontFamily = uiFont.family),
                uiFont,
            )
        } else {
            JewelTheme.createDefaultTextStyle()
        }
        val editorTextStyle = if (substituteMono) {
            IslandFontFamilies.substituteInto(
                JewelTheme.createEditorTextStyle(fontFamily = monoFont.family),
                monoFont,
                IslandFontFamilies.MonoLineHeightMultiplier,
            )
        } else {
            JewelTheme.createEditorTextStyle()
        }
        if (isDark) {
            JewelTheme.darkThemeDefinition(
                colors = globalColors,
                disabledAppearanceValues = DisabledAppearanceValues.dark(),
                defaultTextStyle = defaultTextStyle,
                editorTextStyle = editorTextStyle,
            )
        } else {
            JewelTheme.lightThemeDefinition(
                colors = globalColors,
                disabledAppearanceValues = DisabledAppearanceValues.light(),
                defaultTextStyle = defaultTextStyle,
                editorTextStyle = editorTextStyle,
            )
        }
    }

    // Rebuild the baked-accent component styles (buttons, progress, selection) when the theme
    // provides an accent; otherwise keep Jewel's stock styling.
    val accentStyling = remember(isDark, palette) { buildAccentStyling(palette, isDark) }
    val baseStyling = accentStyling ?: ComponentStyling.default()

    // The bar's own background, in the theme's luminance: see the explanation at the call site.
    val invisibleBar = remember(islands) { islands.frameArgb.toInvisibleColor() }

    // UI text size as a density font scale: every sp in the app — Jewel's hardcoded 13.sp ramps
    // included — multiplies through it, which is the one mechanism that reaches all text at once.
    // dp layout is untouched, so only the type grows, not the chrome around it.
    val baseDensity = LocalDensity.current
    val textScale = IslandFontFamilies.normalizeTextScalePercent(uiTextScalePercent) / 100f
    val density = remember(baseDensity, textScale) {
        if (textScale == 1f) baseDensity else Density(baseDensity.density, baseDensity.fontScale * textScale)
    }

    CompositionLocalProvider(
        LocalThemePalette provides palette,
        LocalIslandsPalette provides islands,
    ) {
        IntUiTheme(
            theme = themeDefinition,
            styling = baseStyling.decoratedWindow(
                // Islands: the bar owns no background of its own. The window's frame layer
                // (Modifier.islandAmbientFrame) paints the flat frame color *plus* the ambient glow
                // behind the bar, so the bar has to let it through - otherwise its opaque band would
                // cover the glow and the strip would read as a separate, dead-colored header.
                //
                // "Let it through" cannot be plain Color.Transparent: Jewel feeds the bar's
                // background into `OverrideDarkMode(background.isDark())` so the window controls pick
                // matching glyph variants, and `isDark()` ignores alpha - a transparent black would
                // therefore force dark glyphs onto a light theme's bar. A zero-alpha color whose RGB
                // *is* the active frame keeps that lookup correct while still painting nothing, and
                // it follows the theme, so a plugin that repaints the chrome gets the right glyphs.
                titleBarStyle = if (isDark) {
                    TitleBarStyle.dark(
                        colors = TitleBarColors.dark(
                            backgroundColor = invisibleBar,
                            inactiveBackground = invisibleBar,
                            borderColor = invisibleBar,
                        ),
                    )
                } else {
                    TitleBarStyle.lightWithLightHeader(
                        colors = TitleBarColors.lightWithLightHeader(
                            backgroundColor = invisibleBar,
                            inactiveBackground = invisibleBar,
                            borderColor = invisibleBar,
                        ),
                    )
                },
            ),
            content = {
                // The overlay scrollbar is provided *inside* IntUiTheme so it shadows the styling the
                // theme installs (JewelTheme.scrollbarStyle reads LocalScrollbarStyle), which puts every
                // Jewel scroll container in the app — lists, trees, panels, dialogs — on the IDE's
                // hover-only track without any call site opting in.
                val scrollbarStyle = islandOverlayScrollbarStyle()
                // The typography override shadows the IntUiTypography IntUiTheme just installed:
                // JewelTheme.typography reads LocalTypography, and Blaze's chrome styles and the
                // console panes all derive from it, so the chosen families propagate everywhere.
                val baseTypography = LocalTypography.current
                val typography = remember(baseTypography, uiFontFamilyId, monoFontFamilyId) {
                    if (!substituteUi && !substituteMono) {
                        baseTypography
                    } else {
                        FontSubstitutingTypography(baseTypography, uiFont, monoFont, substituteUi, substituteMono)
                    }
                }
                CompositionLocalProvider(
                    LocalTypography provides typography,
                    LocalDensity provides density,
                    LocalScrollbarStyle provides scrollbarStyle,
                    content = content,
                )
            },
        )
    }
}

/**
 * The frame color with its alpha zeroed: paints nothing, but still carries the theme's luminance
 * for Jewel's `isDark()` glyph lookup (which reads RGB only, never alpha).
 */
private fun Long?.toInvisibleColor(): Color {
    val base = this?.toColor() ?: IslandsDefaults.FRAME_DARK.toColor()
    return Color(base.red, base.green, base.blue, alpha = 0f)
}
