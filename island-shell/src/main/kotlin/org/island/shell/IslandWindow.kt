package org.island.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import org.island.foundation.window.DialogChromeGuard
import org.island.theme.ui.IslandChromeColors
import org.island.theme.ui.LocalAmbientGlowEnabled
import org.island.theme.ui.LocalShortcutLabels
import org.island.theme.ui.LocalWindowActive
import org.island.theme.ui.ShortcutLabels
import org.island.ui.notifications.IslandClipboard
import org.island.ui.notifications.LocalIslandClipboard
import org.island.ui.notifications.LocalNotifications
import org.island.ui.notifications.NotificationHost
import org.island.ui.notifications.NotificationsState
import org.island.ui.text.IslandUiStrings
import org.island.ui.text.LocalIslandUiStrings
import org.island.ui.window.applyWindowsTitleBarTheme
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.DecoratedWindowScope
import java.awt.Dimension
import java.awt.Frame
import java.awt.Image
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt
import java.awt.Color as AwtColor

/**
 * The shell's parameterized app window. Owns every AWT/Jewel interaction a host app would
 * otherwise repeat:
 *
 *  - The Jewel [DecoratedWindow] itself (custom title bar + auto-emitted window controls);
 *  - The frame-color pinning so the 1dp border row Jewel reserves matches the active Islands
 *    theme (`IslandChromeColors.frame`), both on the Compose side and on the AWT background and
 *    contentPane so no pixel leaks the Swing look-and-feel;
 *  - The DWM caption / border theming on Windows through [applyWindowsTitleBarTheme] (a silent
 *    no-op elsewhere);
 *  - The `ICONIFIED -> hide-to-tray` folding - a title-bar minimize on Linux sets
 *    `window.extendedState = ICONIFIED` which is invisible to the app's own `windowVisible`
 *    state, so the listener un-iconifies and calls [IslandWindowHost.onMinimize]. While a modal
 *    dialog is composed the iconify can only have come from a dialog caption, so the frame is
 *    just brought back instead of hidden;
 *  - The tray hide/show raise: driven through DecoratedWindow's own `visible` parameter so
 *    Compose maps/unmaps the frame and keeps its render surface in sync. Bumping
 *    [IslandWindowHost.windowRaiseToken] re-runs the raise even when the window was already
 *    visible - which is how an external open (a magnet click) drags the frame in front of the
 *    user's other windows;
 *  - The root [CompositionLocalProvider] set island components read: [LocalNotifications],
 *    [LocalIslandUiStrings], [LocalIslandClipboard], [LocalWindowActive], [LocalShortcutLabels],
 *    [LocalAmbientGlowEnabled];
 *  - The frame-level `onPreviewKeyEvent` half of the keymap bridge - the app's [onPreviewKeyEvent]
 *    runs for every key event while the frame is focused, independent of Compose's focus state,
 *    so a shortcut cannot be displayed without being handled;
 *  - The whole-window drop target + overlay (see [rememberIslandWindowDrop] and [IslandDropOverlay]
 *    - pass the value returned by the remember into [drop]);
 *  - The `NotificationHost` aligned to `BottomEnd` inside the body Box, so drop feedback and every
 *    screen's balloon render above the app content without each screen stacking its own host.
 *
 * Everything app-specific lives in the slots: [titleBar] draws the chrome above the body (Blaze
 * uses [IslandTitleBar] here), [content] is the body, [contentOverlays] renders floating inside the
 * body Box (a search popup, a modal host, a taskbar progress overlay), and [dialogs] is composed
 * outside the Box so a host can hang its own [androidx.compose.ui.window.DialogWindow]s (Settings,
 * consent, ...) off the same owner frame.
 *
 * The frame's minimum size and OS taskbar icon are exposed as parameters - Blaze pins `760x480` and
 * reads `AppTrayIcon.windowIcon()`; a demo app can leave both as defaults.
 */
@Composable
fun IslandWindow(
    host: IslandWindowHost,
    title: String,
    shortcutLabels: ShortcutLabels,
    titleBar: @Composable DecoratedWindowScope.() -> Unit,
    content: @Composable () -> Unit,
    minimumSizePx: Pair<Int, Int> = 760 to 480,
    windowIcon: () -> Image? = { null },
    ambientGlowEnabled: Boolean = true,
    strings: IslandUiStrings = IslandUiStrings.Default,
    clipboard: IslandClipboard? = null,
    notifications: NotificationsState = remember { NotificationsState() },
    drop: IslandWindowDrop = IslandWindowDrop.None,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    contentOverlays: @Composable BoxScope.() -> Unit = {},
    dialogs: @Composable () -> Unit = {},
) {
    DecoratedWindow(
        onCloseRequest = host::onCloseRequest,
        state = host.windowState,
        visible = host.windowVisible.value,
        title = title,
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        val visible = host.windowVisible.value
        val raiseToken = host.windowRaiseToken.value
        // Nothing in the scene paints the 1dp row Jewel's title bar reserves for its border, and the
        // Skia surface leaves unpainted pixels transparent - so they show the AWT side of the window.
        // JFrame's default is the Swing look-and-feel's panel color (#eeeeee under Metal/Ocean), a hard
        // light line across the dark chrome. Pin the window's own background to the frame color so any
        // gap in the composition reads as chrome instead of a leaked light-theme token.
        val frameColor = IslandChromeColors.frame
        // DWM still owns the OS window frame (the 1px border and, on Windows 11, the rounded
        // corners and drop shadow) even though Jewel paints the bar's contents. Pin those to the
        // active Islands frame color so the app window's native chrome tracks the theme the same
        // way its title-bar contents already do; the keyed effect re-applies the moment the theme
        // (mode or a color edit) changes. A silent no-op everywhere but Windows.
        applyWindowsTitleBarTheme(window, frameColor)
        LaunchedEffect(window, frameColor, minimumSizePx) {
            window.minimumSize = Dimension(minimumSizePx.first, minimumSizePx.second)
            // No `.desktop` entry matches an IDE launch, so the taskbar reads the icon
            // from the frame's `_NET_WM_ICON`; AWT only sets that from `iconImage`.
            windowIcon()?.let { window.iconImage = it }
            window.background = frameColor.toAwtColor()
            window.contentPane.background = frameColor.toAwtColor()
        }

        // The title-bar minimize button (Linux) sets `window.extendedState = ICONIFIED`,
        // which is invisible to the app's `windowVisible` state: the tray keeps reading the window
        // as visible (still offering "Hide Window"), and the leftover ICONIFIED state later stops a
        // tray "Show" from re-mapping the frame. Fold to minimize into the app's hide model:
        // un-iconify and report it as hidden.
        // NOTE: `windowStateChanged` is a WindowStateListener callback, so the adapter must be
        // registered with addWindowStateListener (addWindowListener alone never fires it).
        // NOTE: dialogs are not minimizable (see DialogChromeGuard's registry), but a window
        // manager that iconifies transient groups still lands an ICONIFIED on this frame when the
        // user presses a dialog's caption button. While a dialog is composed that is the *only* way
        // the frame can iconify - the modal blocks the app's own title-bar buttons - so the
        // minimize is simply undone instead of hiding the app to the tray.
        DisposableEffect(window, host) {
            val listener = object : WindowAdapter() {
                override fun windowStateChanged(e: WindowEvent) {
                    if (e.newState and Frame.ICONIFIED != 0) {
                        window.extendedState = Frame.NORMAL
                        if (DialogChromeGuard.hasOpenDialog()) {
                            // Dialog caption minimize (direct or group-propagated): bring the
                            // frame and its transient dialog back and keep the app shown.
                            window.toFront()
                            window.requestFocus()
                        } else {
                            host.onMinimize()
                        }
                    }
                }
            }
            window.addWindowStateListener(listener)
            onDispose { window.removeWindowStateListener(listener) }
        }

        // Tray hide/show: drive visibility through DecoratedWindow's own `visible` parameter so
        // Compose maps/unmaps the frame and keeps its render surface in sync. Toggling the AWT
        // `window.isVisible` behind Compose's back hides the frame fine, but re-showing it leaves
        // the surface un-repainted (the window never comes back). The composition - and whatever
        // services the app holds there - stay alive while hidden either way. Clearing ICONIFIED plus
        // raising/focusing covers a re-show of a frame that KDE left minimized. [raiseToken] bumps
        // re-run the raise even when the window was already visible, so an external open arriving
        // while the app is shown still drags the frame in front of the user's other windows.
        LaunchedEffect(visible, raiseToken) {
            if (visible) {
                window.extendedState = Frame.NORMAL
                window.toFront()
                window.requestFocus()
            }
        }

        CompositionLocalProvider(
            LocalNotifications provides notifications,
            LocalIslandUiStrings provides strings,
            LocalIslandClipboard provides clipboard,
            LocalWindowActive provides state.isActive,
            LocalShortcutLabels provides shortcutLabels,
            LocalAmbientGlowEnabled provides ambientGlowEnabled,
        ) {
            // The title bar lives inside the providers so its menu tooltips and the hamburger's own
            // shortcut labels read the same OS-formatted accelerators as the rest of the chrome.
            titleBar()

            Box(modifier = Modifier.fillMaxSize().then(drop.modifier)) {
                content()
                // Whole-window drop feedback sits above the body but never registers as a target,
                // so it cannot steal the gesture from the modifier above.
                IslandDropOverlay(drop)
                // Balloons live at the window level so drop feedback shows on every screen, and
                // screens report through the same queue via LocalNotifications instead of stacking
                // their own hosts.
                NotificationHost(
                    state = notifications,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                contentOverlays()
            }

            // Any modal window (Settings, a consent queue, ...) the host hangs off the frame's owner
            // context so the DialogWindow inherits the theme, strings, RTL and shortcut labels
            // provided around [content] above.
            dialogs()
        }
    }
}

/**
 * The AWT color for a Compose color, for the handful of properties only the Swing side of the window
 * exposes. Alpha is dropped on purpose: a translucent window background would make the frame
 * see-through, and every surface token in the app is opaque.
 */
private fun Color.toAwtColor(): AwtColor =
    AwtColor(
        (red.coerceIn(0f, 1f) * 255).roundToInt(),
        (green.coerceIn(0f, 1f) * 255).roundToInt(),
        (blue.coerceIn(0f, 1f) * 255).roundToInt(),
    )
