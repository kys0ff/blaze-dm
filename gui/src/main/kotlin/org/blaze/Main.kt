package org.blaze

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.runBlocking
import org.blaze.ui.components.ToolbarIconButton
import org.blaze.ui.screens.MainScreen
import org.jetbrains.jewel.foundation.DisabledAppearanceValues
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
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
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.jetbrains.jewel.window.utils.clientRegion
import java.awt.Dimension

fun main() = application {
    var isDark by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(1100.dp, 720.dp))

    val themeDefinition =
        remember(isDark) {
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
            titleBarStyle = if (isDark) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader(),
        ),
    ) {
        DecoratedWindow(
            onCloseRequest = {
                runBlocking { Di.engine.shutdown() }
                exitApplication()
            },
            state = windowState,
            title = "Blaze",
        ) {
            // The title bar has a fixed-width search field plus left/right clusters,
            // so don't let the window shrink until they overlap.
            LaunchedEffect(window) { window.minimumSize = Dimension(760, 480) }

            BlazeTitleBar(
                isDark = isDark,
                onToggleDark = { isDark = !isDark },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(JewelTheme.globalColors.panelBackground),
            ) {
                Navigator(MainScreen())
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun DecoratedWindowScope.BlazeTitleBar(
    isDark: Boolean,
    onToggleDark: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }

    TitleBar(
        modifier = Modifier.newFullscreenControls(),
    ) {
        // App identity, like the IDE's leading logo + project widget.
        Row(
            modifier = Modifier
                .align(Alignment.Start)
                .height(40.dp)
                .padding(start = 8.dp)
                .clientRegion("title_bar_left"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                key = AllIconsKeys.Actions.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Blaze",
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.width(8.dp))
            Divider(Orientation.Vertical, modifier = Modifier.height(20.dp))
        }

        // Search field in the style of the IDE's Search Everywhere entry:
        // leading magnifier, clear button when there's text, Esc to clear.
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search downloads…") },
            leadingIcon = {
                Icon(
                    key = AllIconsKeys.Actions.Find,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = if (query.text.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { query = TextFieldValue("") },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Close,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(420.dp)
                .height(32.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape &&
                        query.text.isNotEmpty()
                    ) {
                        query = TextFieldValue("")
                        true
                    } else {
                        false
                    }
                }
                .clientRegion("search"),
        )

        // Toolbar actions use the same ActionButton style (hover highlight + tooltip)
        // as the rest of the app.
        Row(
            modifier = Modifier
                .align(Alignment.End)
                .height(40.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarIconButton(
                key = if (isDark) AllIconsKeys.MeetNewUi.LightTheme else AllIconsKeys.MeetNewUi.DarkTheme,
                tooltip = if (isDark) "Switch to light theme" else "Switch to dark theme",
                onClick = onToggleDark,
                modifier = Modifier.clientRegion("theme_button"),
            )
        }
    }
}