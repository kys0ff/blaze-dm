package org.blaze.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import org.blaze.presentation.components.BlazeLayout
import org.blaze.theming.api.ThemePalette
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
 * A color that paints nothing but still carries a theme's luminance, so a component that infers
 * light/dark from its background (Jewel's title bar does) keeps choosing the right glyph variants
 * even when the actual pixels come from somewhere else. `isDark()` reads RGB only, never alpha.
 */
private val InvisibleBlack = Color(red = 0f, green = 0f, blue = 0f, alpha = 0f)
private val InvisibleWhite = Color(red = 1f, green = 1f, blue = 1f, alpha = 0f)

/**
 * The single theming entry point.
 *
 * [palette] is the theme resolved by [org.blaze.theming.core.ThemeRegistry] for the current
 * light/dark [isDark] choice. It drives two layers at once:
 *  - the Jewel surfaces, via [buildJewelGlobalColors] folded into the [ThemeDefinition]; and
 *  - the app's semantic colors, exposed through [LocalThemePalette] to [BlazeColors].
 *
 * Passing an empty palette (the default) reproduces the stock Int UI look.
 */
@Composable
fun BlazeTheme(
    palette: ThemePalette = ThemePalette.Empty,
    isDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val themeDefinition = remember(isDark, palette) {
        val globalColors = buildJewelGlobalColors(isDark, palette)
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

    CompositionLocalProvider(LocalThemePalette provides palette) {
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
                // therefore force dark glyphs onto the light bar. A zero-alpha color whose RGB *does*
                // match the theme keeps that lookup correct while still painting nothing.
                titleBarStyle = if (BlazeLayout.USE_ISLANDS) {
                    if (isDark) {
                        TitleBarStyle.dark(
                            colors = TitleBarColors.dark(
                                backgroundColor = InvisibleBlack,
                                inactiveBackground = InvisibleBlack,
                                borderColor = InvisibleBlack,
                            ),
                        )
                    } else {
                        TitleBarStyle.lightWithLightHeader(
                            colors = TitleBarColors.lightWithLightHeader(
                                backgroundColor = InvisibleWhite,
                                inactiveBackground = InvisibleWhite,
                                borderColor = InvisibleWhite,
                            ),
                        )
                    }
                } else {
                    // Classic: the stock banded header, drawn by Jewel itself over the frame color.
                    if (isDark) {
                        TitleBarStyle.dark(
                            colors = TitleBarColors.dark(
                                backgroundColor = BlazeIslandColors.frameFor(isDark = true),
                                inactiveBackground = BlazeIslandColors.frameFor(isDark = true),
                                borderColor = BlazeIslandColors.frameFor(isDark = true),
                            ),
                        )
                    } else {
                        TitleBarStyle.lightWithLightHeader(
                            colors = TitleBarColors.lightWithLightHeader(
                                backgroundColor = BlazeIslandColors.frameFor(isDark = false),
                                inactiveBackground = BlazeIslandColors.frameFor(isDark = false),
                                borderColor = BlazeIslandColors.frameFor(isDark = false),
                            ),
                        )
                    }
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
