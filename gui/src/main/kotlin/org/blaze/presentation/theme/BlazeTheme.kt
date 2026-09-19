package org.blaze.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

@Composable
fun BlazeTheme(
    isDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val themeDefinition = remember(isDark) {
        if (isDark) {
            JewelTheme.darkThemeDefinition(
                disabledAppearanceValues = DisabledAppearanceValues.dark(),
            )
        } else {
            JewelTheme.lightThemeDefinition(
                disabledAppearanceValues = DisabledAppearanceValues.light(),
            )
        }
    }

    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.default().decoratedWindow(
            titleBarStyle = if (isDark) {
                TitleBarStyle.dark()
            } else {
                TitleBarStyle.lightWithLightHeader()
            },
        ),
        content = content,
    )
}