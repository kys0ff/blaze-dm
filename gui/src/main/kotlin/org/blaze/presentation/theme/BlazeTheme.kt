package org.blaze.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * The single theming entry point.
 *
 * [palette] is the theme resolved by [org.blaze.theming.core.ThemeRegistry] for the current
 * light/dark [isDark] choice. It drives two layers at once:
 *  - the Jewel surfaces, via [buildJewelGlobalColors] folded into the [ThemeDefinition]; and
 *  - the app's semantic colours, exposed through [LocalThemePalette] to [BlazeColors].
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
                titleBarStyle = if (isDark) {
                    TitleBarStyle.dark()
                } else {
                    TitleBarStyle.lightWithLightHeader()
                },
            ),
            content = content,
        )
    }
}
