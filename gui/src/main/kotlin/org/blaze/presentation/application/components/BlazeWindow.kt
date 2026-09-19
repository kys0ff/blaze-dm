package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.WindowState
import org.jetbrains.jewel.window.DecoratedWindow
import java.awt.Dimension

@Composable
fun BlazeWindow(
    windowState: WindowState,
    onCloseRequest: () -> Unit,
    isDark: Boolean,
    onToggleDark: () -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "Blaze",
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(760, 480)
        }

        BlazeTitleBar(
            isDark = isDark,
            onToggleDark = onToggleDark,
        )

        BlazeContent()
    }
}