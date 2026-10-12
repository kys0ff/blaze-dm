package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import org.blaze.data.AppSettingsRepository
import org.blaze.data.KeymapPersistence
import org.blaze.i18n.blazeStrings
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.tray.AppTrayIcon
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.application.AppShell
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.DialogChromeGuard
import org.blaze.presentation.components.LocalAmbientGlowEnabled
import org.blaze.presentation.components.LocalShortcutLabels
import org.blaze.presentation.components.LocalWindowActive
import org.blaze.presentation.components.notifications.LocalNotifications
import org.blaze.presentation.components.notifications.NotificationHost
import org.blaze.presentation.components.notifications.NotificationsState
import org.blaze.presentation.components.platformShortcutLabels
import org.blaze.presentation.search.SearchController
import org.blaze.presentation.search.SearchEverywherePopup
import org.blaze.presentation.theme.BlazeIslandColors
import org.blaze.security.ConsentService
import org.blaze.server.DownloadServiceController
import org.jetbrains.jewel.window.DecoratedWindow
import org.koin.compose.koinInject
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt
import java.awt.Color as AwtColor

/**
 * The AWT color for a Compose color, for the handful of properties only the Swing side of the window
 * exposes. Alpha is dropped on purpose: a translucent window background would make the frame see-through, and every surface token in the app is opaque.
 */
private fun Color.toAwtColor(): AwtColor =
    AwtColor(
        (red.coerceIn(0f, 1f) * 255).roundToInt(),
        (green.coerceIn(0f, 1f) * 255).roundToInt(),
        (blue.coerceIn(0f, 1f) * 255).roundToInt(),
    )

@Composable
fun BlazeWindow(shell: AppShell) {
    // Search Everywhere is a single window-level popup: the title-bar icon, the hamburger item and
    // Ctrl/Cmd+K all route into this one controller, so all three open the very same thing. It is read
    // here (above the frame) because the frame's own key preview — which must fire before any focused
    // child swallows the chord — drives it.
    val searchController = koinInject<SearchController>()
    DecoratedWindow(
        onCloseRequest = shell::onCloseRequest,
        state = shell.windowState,
        visible = shell.windowVisible.value,
        title = "Blaze",
        // The frame-level half of the keymap bridge. Compose's `Modifier.onPreviewKeyEvent` only fires
        // when a focusable node inside that modifier's subtree owns keyboard focus, so at cold start —
        // with focus nowhere in the scene — every app-wide chord was silently dropped. The window's own
        // preview runs for every key event while the frame is focused, independent of the Compose focus
        // state, and recognizes the very [BlazeKeymap.Binding] the menus print, so a shortcut can no
        // longer be displayed without being handled. The frame owns the two chords that belong to the
        // window itself; everything the downloads screen owns is left to fall through to it.
        onPreviewKeyEvent = { event ->
            when (BlazeKeymap.matchAction(event)) {
                BlazeKeymap.Action.OpenSettings -> {
                    shell.openSettings()
                    true
                }

                BlazeKeymap.Action.SearchEverywhere -> {
                    searchController.toggle()
                    true
                }

                else -> false
            }
        },
    ) {
        val visible = shell.windowVisible.value
        val raiseToken = shell.windowRaiseToken.value
        // Nothing in the scene paints the 1dp row Jewel's title bar reserves for its border, and the
        // Skia surface leaves unpainted pixels transparent — so they show the AWT side of the window.
        // JFrame's default is the Swing look-and-feel's panel color (#eeeeee under Metal/Ocean), a hard
        // light line across the dark chrome. Pin the window's own background to the frame color so any
        // gap in the composition reads as chrome instead of a leaked light-theme token.
        val frameColor = BlazeIslandColors.frame
        LaunchedEffect(window, frameColor) {
            window.minimumSize = Dimension(760, 480)
            // No `.desktop` entry matches an IDE launch, so the taskbar reads the icon
            // from the frame's `_NET_WM_ICON`; AWT only sets that from `iconImage`.
            window.iconImage = AppTrayIcon.windowIcon()
            window.background = frameColor.toAwtColor()
            window.contentPane.background = frameColor.toAwtColor()
        }

        // The title-bar minimize button (Linux) sets `window.extendedState = ICONIFIED`,
        // which is invisible to the app's `windowVisible` state: the tray keeps reading
        // the window as visible (still offering "Hide Window"), and the leftover ICONIFIED
        // state later stops a tray "Show" from re-mapping the frame. Fold to minimize into
        // the single hide-to-tray model: un-iconify and report it as hidden.
        // NOTE: `windowStateChanged` is a WindowStateListener callback, so the adapter must
        // be registered with addWindowStateListener (addWindowListener alone never fires it).
        // NOTE: dialogs are not minimizable (see [preventDialogMinimize]), but a window manager
        // that iconifies transient groups still lands an ICONIFIED on this frame when the user
        // presses a dialog's caption button. While a dialog is composed that is the *only* way
        // the frame can iconify - the modal blocks the app's own title-bar buttons - so the
        // minimize is simply undone instead of hiding the app to the tray.
        DisposableEffect(window) {
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
                            shell.onMinimize()
                        }
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

        // Whole-window drag-and-drop: Compose's own drop plumbing (a frame-level AWT DropTarget
        // never fires on Linux because drops are delivered to the Compose surface itself). The
        // modifier on the content root catches URLs / magnets / .torrent files / link lists /
        // partial downloads dropped anywhere over the window and routes the validated ones into
        // the external-open inbox - the exact same queue browser handlers and IPC forwards feed.
        // Balloons live at the window level so drop feedback shows on every screen, and screens
        // report through the same queue via LocalNotifications instead of stacking their own hosts.
        val notifications = remember { NotificationsState() }
        val inbox = koinInject<ExternalOpenInbox>()
        val parser = koinInject<ExternalOpenParser>()
        val drop = rememberWindowDropTarget(inbox, parser, notifications)

        // Window activity and the platform keymap are surfaced app-wide: rows switch to the IDE's
        // gray inactive-window selection when the frame loses focus, and every tooltip/menu reads
        // the same OS-formatted accelerator labels instead of hard-coding "Ctrl"/"Cmd".
        val shortcutLabels = remember { platformShortcutLabels() }
        // The ambient glow is a chrome preference, so it lives with the other shell settings and is
        // read reactively here: Apply in Settings changes the frame immediately without a restart.
        val appSettings = koinInject<AppSettingsRepository>().settings.collectAsState().value
        // The persisted keymap is re-read reactively: Apply in Settings rebinds a chord and the frame's
        // own preview (plus every printed accelerator) follows without a restart.
        LaunchedEffect(appSettings) {
            KeymapPersistence.applyTo(appSettings.keyBindings, appSettings.unboundShortcuts)
        }
        val commandBus = koinInject<AppCommandBus>()
        val strings = blazeStrings
        val serverController = koinInject<DownloadServiceController>()
        val clipboard = koinInject<SystemClipboard>()
        LaunchedEffect(commandBus, searchController) {
            commandBus.commands.collect { command ->
                when (command) {
                    AppCommand.SearchEverywhere -> searchController.toggle()

                    // Copy the running server's endpoint and token path to the clipboard, and confirm it
                    // with a balloon - the one command that is answered here because it needs both the
                    // window's notification stack and the system clipboard.
                    AppCommand.CopyServerEndpoint -> {
                        val status = serverController.status.value
                        val endpoint = status.endpoint
                        if (endpoint == null) {
                            notifications.warning(strings.serverLog.stopped)
                        } else {
                            val payload = "$endpoint\n${strings.serverLog.tokenFile}: ${status.tokenFile}"
                            clipboard.copy(payload)
                                .onSuccess { notifications.info(strings.menu.endpointCopied) }
                                .onFailure { notifications.error(it.message ?: strings.menu.copyEndpoint) }
                        }
                    }

                    else -> Unit
                }
            }
        }
        CompositionLocalProvider(
            LocalNotifications provides notifications,
            LocalWindowActive provides state.isActive,
            LocalShortcutLabels provides shortcutLabels,
            LocalAmbientGlowEnabled provides appSettings.ambientGlowEnabled,
        ) {
            // The title bar lives inside the providers so its menu tooltips and the hamburger's own
            // shortcut labels read the same OS-formatted accelerators as the rest of the chrome.
            BlazeTitleBar(shell)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(drop.modifier),
            ) {
                BlazeContent()
                WindowDropOverlay(drop)
                NotificationHost(
                    state = notifications,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                // Rendered at the window root so it floats above every screen, exactly like the menu
                // popups; it reads all state from the shared controller and no-ops when closed.
                SearchEverywherePopup(searchController)
            }

            // The modal Settings window. Composing it here is what gives it the right owner and the
            // right context: Compose's DialogWindow takes its owner from LocalAwtWindow (provided by
            // this window's panel), so the dialog is document-modal to the main frame, stays above it
            // and never appears in the taskbar; and it inherits the strings, layout direction, theme
            // and shortcut labels provided around BlazeContent, so its own chrome matches the rest of
            // the app in light, dark and RTL. The state is hoisted outside the `if` so the user's
            // resize and drag survive closing and reopening Settings within a session.
            val settingsDialogState = rememberDialogState(
                position = WindowPosition(Alignment.Center),
                width = BlazeLayout.SettingsDialogWidth,
                height = BlazeLayout.SettingsDialogHeight,
            )
            if (shell.settingsOpen.value) {
                SettingsDialogWindow(
                    state = settingsDialogState,
                    onDismiss = shell::closeSettings,
                )
            }

            // The security consent queue, rendered one dialog at a time (oldest first) above every
            // screen. It lives here — composed inside the frame and around the same providers as the
            // rest of the content — so the DialogWindow takes Blaze's frame as its owner (document-modal, no taskbar entry) and the copy follows the app strings, theme and RTL like
            // everything else. The shell has already raised the frame when the request arrived;
            // answering (or the request being withdrawn) removes the dialog from the composition and
            // the suspended caller resumes on the service side.
            val consentService = koinInject<ConsentService>()
            val pendingConsent by consentService.pending.collectAsState()
            pendingConsent.firstOrNull()?.let { request ->
                ConsentDialogWindow(
                    request = request,
                    onDecision = { decision -> consentService.respond(request.id, decision) },
                )
            }
        }
    }
}
