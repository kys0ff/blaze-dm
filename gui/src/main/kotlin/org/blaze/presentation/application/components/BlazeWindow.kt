package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.WindowState
import org.jetbrains.jewel.window.DecoratedWindow
import java.awt.Dimension

@Composable
fun BlazeWindow(
    windowState: WindowState,
    visible: Boolean,
    onCloseRequest: () -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "Blaze",
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(760, 480)
        }

        // Tray hide/show: the Compose window's peer is an AWT frame, so toggling its
        // visibility keeps the composition (and the download engine) alive while hidden.
        LaunchedEffect(window, visible) {
            if (visible) {
                if (!window.isVisible) window.isVisible = true
                window.toFront()
                window.requestFocus()
            } else {
                window.isVisible = false
            }
        }

        BlazeTitleBar()

        BlazeContent()
    }
}