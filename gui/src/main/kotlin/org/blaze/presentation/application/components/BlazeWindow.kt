package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.WindowState
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.platform.tray.AppTrayIcon
import org.jetbrains.jewel.window.DecoratedWindow
import org.koin.compose.koinInject
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

@Composable
fun BlazeWindow(
    windowState: WindowState,
    visible: Boolean,
    raiseToken: Long,
    onCloseRequest: () -> Unit,
    onMinimize: () -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onCloseRequest,
        state = windowState,
        visible = visible,
        title = "Blaze",
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(760, 480)
            // No `.desktop` entry matches an IDE launch, so the taskbar reads the icon
            // from the frame's `_NET_WM_ICON`; AWT only sets that from `iconImage`.
            window.iconImage = AppTrayIcon.windowIcon()
        }

        // The title-bar minimize button (Linux) sets `window.extendedState = ICONIFIED`,
        // which is invisible to the app's `windowVisible` state: the tray keeps reading
        // the window as visible (still offering "Hide Window"), and the leftover ICONIFIED
        // state later stops a tray "Show" from re-mapping the frame. Fold to minimize into
        // the single hide-to-tray model: un-iconify and report it as hidden.
        // NOTE: `windowStateChanged` is a WindowStateListener callback, so the adapter must
        // be registered with addWindowStateListener (addWindowListener alone never fires it).
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowStateChanged(e: WindowEvent) {
                    if (e.newState and Frame.ICONIFIED != 0) {
                        window.extendedState = Frame.NORMAL
                        onMinimize()
                    }
                }
            }
            window.addWindowStateListener(listener)
            onDispose { window.removeWindowStateListener(listener) }
        }

        // Tray hide/show: drive visibility through DecoratedWindow's own `visible`
        // parameter so Compose maps/unmaps the frame and keeps its render surface in
        // sync. Toggling the AWT `window.isVisible` behind Compose's back hides the
        // frame fine, but re-showing it leaves the surface un-repainted (the window
        // never comes back). The composition - and the download engine - stay alive
        // while hidden either way. Clearing ICONIFIED plus raising/focusing covers a
        // re-show of a frame that KDE left minimized. [raiseToken] bumps re-run the raise even
        // when the window was already visible, so an external open (magnet/torrent) while the
        // app is shown still drags the frame in front of the user's other windows.
        LaunchedEffect(visible, raiseToken) {
            if (visible) {
                window.extendedState = Frame.NORMAL
                window.toFront()
                window.requestFocus()
            }
        }

        BlazeTitleBar()

        // Whole-window drag-and-drop: a frame-level DropTarget catches URLs / magnets / .torrent
        // files dropped anywhere over the window and routes them into the external-open inbox, so
        // the drop reuses the exact same validated queue as browser handlers and IPC forwards.
        val inbox = koinInject<ExternalOpenInbox>()
        val parser = koinInject<ExternalOpenParser>()
        val dragInProgress = rememberWindowDropTarget(window, inbox, parser)

        Box(modifier = Modifier.fillMaxSize()) {
            BlazeContent()
            if (dragInProgress) {
                WindowDropOverlay()
            }
        }
    }
}
