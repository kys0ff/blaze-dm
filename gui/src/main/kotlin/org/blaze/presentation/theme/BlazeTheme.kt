package org.blaze.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import org.blaze.theming.api.IslandsDefaults
import org.blaze.theming.api.ThemePalette
import org.blaze.theming.api.withFallback
import org.jetbrains.jewel.foundation.DisabledAppearanceValues
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.light
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.LocalScrollbarStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * The single theming entry point.
 *
 * Blaze renders exactly one design system — the JetBrains New UI "Islands" shell — and this
 * function is where the shell's resolved tokens and the active theme meet. [palette] is the theme
 * resolved by [org.blaze.theming.core.ThemeRegistry] for the current light/dark [isDark] choice;
 * it drives three layers at once:
 *  - the Islands chrome, completed from [IslandsDefaults] and exposed through [LocalIslandsPalette]
 *    (read by [BlazeIslandColors], the frame layer and every island);
 *  - the Jewel surfaces, via [buildJewelGlobalColors] folded into the `ThemeDefinition`; and
 *  - the app's semantic colors, exposed through [LocalThemePalette] to [BlazeColors].
 *
 * Passing an empty palette reproduces the stock Islands look, because resolution always ends by
 * filling unset tokens from the design system — a theme can never leave the chrome unpainted.
 */
@Composable
fun BlazeTheme(
    palette: ThemePalette = ThemePalette.Empty,
    isDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The chrome the whole window renders with: the theme's Islands overrides, completed from the
    // stock design-system values so no token can be left unset by a partial theme.
    val islands = remember(palette, isDark) {
        palette.islands.withFallback(IslandsDefaults.forMode(isDark))
    }

    val themeDefinition = remember(isDark, palette, islands) {
        val globalColors = buildJewelGlobalColors(isDark, palette, islands)
        if (isDark) {
            JewelTheme.darkThemeDefinition(
                colors = globalColors,
                disabledAppearanceValues = DisabledAppearanceValues.dark(),
            )
        } else {
            JewelTheme.lightThemeDefinition(
                colors = globalColors,
                disabledAppearanceValues = DisabledAppearanceValues.light(),
            )
        }
    }

    // Rebuild the baked-accent component styles (buttons, progress, selection) when the theme
    // provides an accent; otherwise keep Jewel's stock styling.
    val accentStyling = remember(isDark, palette) { buildAccentStyling(palette, isDark) }
    val baseStyling = accentStyling ?: ComponentStyling.default()

    // The bar's own background, in the theme's luminance: see the explanation at the call site.
    val invisibleBar = remember(islands) { islands.frameArgb.toInvisibleColor() }

    CompositionLocalProvider(
        LocalThemePalette provides palette,
        LocalIslandsPalette provides islands,
    ) {
        IntUiTheme(
            theme = themeDefinition,
            styling = baseStyling.decoratedWindow(
                // Islands: the bar owns no background of its own. The window's frame layer
                // (Modifier.blazeAmbientFrame) paints the flat frame color *plus* the ambient glow
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
                val scrollbarStyle = blazeOverlayScrollbarStyle()
                CompositionLocalProvider(LocalScrollbarStyle provides scrollbarStyle, content = content)
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
