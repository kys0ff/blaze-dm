package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.ToolbarIconButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.utils.clientRegion

@Composable
fun TitleBarScope.BlazeTitleBarActions(
    isDark: Boolean,
    onToggleDark: () -> Unit,
) {
    val strings = blazeStrings
    Row(
        modifier = Modifier
            .align(Alignment.End)
            .height(40.dp)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIconButton(
            key = if (isDark) {
                AllIconsKeys.MeetNewUi.LightTheme
            } else {
                AllIconsKeys.MeetNewUi.DarkTheme
            },
            tooltip = if (isDark) {
                strings.common.switchLight
            } else {
                strings.common.switchDark
            },
            onClick = onToggleDark,
            modifier = Modifier.clientRegion("theme_button"),
        )
    }
}