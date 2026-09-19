package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import org.blaze.presentation.components.ToolWindowHeader
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

class SettingsScreen : Screen {
    @Composable
    override fun Content() {
        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(title = "Settings")

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "App configuration will appear here.",
                    color = JewelTheme.globalColors.text.info
                )
            }
        }
    }
}