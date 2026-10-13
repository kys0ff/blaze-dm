package org.blaze.presentation.application.components

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEvent
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
import org.blaze.presentation.components.AppMetrics
import org.blaze.presentation.components.BlazeIslandStrings
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.components.platformShortcutLabels
import org.blaze.presentation.search.SearchController
import org.blaze.presentation.search.SearchEverywherePopup
import org.blaze.security.ConsentService
import org.blaze.server.DownloadServiceController
import org.island.shell.IslandWindow
import org.island.ui.notifications.IslandClipboard
import org.island.ui.notifications.NotificationsState
import org.koin.compose.koinInject

/**
 * Blaze's app window - a thin consumer of [IslandWindow]. The shell owns every AWT/Jewel
 * interaction that a fresh Islands app would repeat (frame color pinning, DWM caption, ICONIFIED
 * folding, tray raise, drop plumbing, notification host, root [CompositionLocalProvider] set,
 * key-preview dispatch); Blaze's file supplies only the state, collaborators, and slots:
 *
 *  - [AppShell] becomes the shell's [org.island.shell.IslandWindowHost];
 *  - [AppSettingsRepository] is read reactively to feed the shell's ambient-glow toggle and the
 *    persisted keymap (applied through [KeymapPersistence] in a keyed effect so Apply in Settings
 *    rebinds a chord without a restart);
 *  - The frame's `onPreviewKeyEvent` runs Blaze's own [BlazeKeymap.matchAction] and folds the two
 *    window-level chords (OpenSettings, SearchEverywhere) into the shell's preview dispatch;
 *  - Blaze's [AppCommandBus] collection (Search Everywhere toggle + Copy Server Endpoint with
 *    clipboard + balloons) stays here because it needs both the window's [NotificationsState] and
 *    Blaze's [SystemClipboard];
 *  - [BlazeTitleBar] draws the bar through [IslandTitleBar][org.island.shell.IslandTitleBar];
 *    [BlazeContent] fills the body; [SearchEverywherePopup] floats above the body as a content
 *    overlay; the modal Settings window and the security consent queue hang off the shell's dialog
 *    slot so each takes this frame as its owner.
 */
@Composable
fun BlazeWindow(shell: AppShell) {
    // Search Everywhere is a single window-level popup: the title-bar icon, the hamburger item and
    // Ctrl/Cmd+K all route into this one controller, so all three open the very same thing. It is
    // read here (above the frame) because the frame's own key preview - which must fire before any
    // focused child swallows the chord - drives it.
    val searchController = koinInject<SearchController>()

    // Balloons live at the window level so drop feedback shows on every screen, and screens report
    // through the same queue via LocalNotifications instead of stacking their own hosts. The shell
    // reads this instance back through LocalNotifications and paints the NotificationHost.
    val notifications = remember { NotificationsState() }

    // The ambient glow is a chrome preference, so it lives with the other shell settings and is
    // read reactively here: Apply in Settings changes the frame immediately without a restart.
    // The persisted keymap is re-read reactively in the same effect: Apply rebinds a chord and the
    // frame's own preview (plus every printed accelerator) follows without a restart.
    val appSettings = koinInject<AppSettingsRepository>().settings.collectAsState().value
    LaunchedEffect(appSettings) {
        KeymapPersistence.applyTo(appSettings.keyBindings, appSettings.unboundShortcuts)
    }

    // Window activity and the platform keymap are surfaced app-wide: rows switch to the IDE's gray
    // inactive-window selection when the frame loses focus, and every tooltip/menu reads the same
    // OS-formatted accelerator labels instead of hard-coding "Ctrl"/"Cmd".
    val shortcutLabels = remember { platformShortcutLabels() }

    val commandBus = koinInject<AppCommandBus>()
    val strings = blazeStrings
    val serverController = koinInject<DownloadServiceController>()
    val clipboard = koinInject<SystemClipboard>()
    // The island components' seams: their labels come from Blaze's live i18n through the adapter,
    // and the error balloon's copy-details link writes through the platform clipboard. Provided at
    // the window root (inside [IslandWindow]) so every island surface (tool windows, balloons,
    // popups) reads the same one.
    val islandStrings = remember(strings) { BlazeIslandStrings(strings) }
    val islandClipboard = remember(clipboard) { IslandClipboard { text -> clipboard.copy(text) } }

    LaunchedEffect(commandBus, searchController, notifications, serverController, clipboard, strings) {
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

    // The frame-level half of the keymap bridge. Compose's `Modifier.onPreviewKeyEvent` only fires
    // when a focusable node inside that modifier's subtree owns keyboard focus, so at cold start -
    // with focus nowhere in the scene - every app-wide chord was silently dropped. The window's own
    // preview runs for every key event while the frame is focused, independent of the Compose focus
    // state, and recognizes the very [org.island.ui.keymap.IslandKeyBinding] the menus print, so a
    // shortcut can no longer be displayed without being handled. The frame owns the two chords that
    // belong to the window itself; everything the downloads screen owns is left to fall through.
    val onPreviewKeyEvent: (KeyEvent) -> Boolean = { event ->
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
    }

    // Whole-window drag-and-drop: the shell's Compose-side drop plumbing catches URLs / magnets /
    // .torrent files / link lists / partial downloads dropped anywhere over the window and routes
    // the validated ones into Blaze's external-open inbox - the exact same queue browser handlers
    // and IPC forwards feed. See [rememberWindowDropTarget].
    val inbox = koinInject<ExternalOpenInbox>()
    val parser = koinInject<ExternalOpenParser>()
    val drop = rememberWindowDropTarget(inbox, parser, notifications)

    // The modal Settings window. Its state is hoisted outside the `if` (below, in the shell's
    // `dialogs` slot) so the user's resize and drag survive closing and reopening Settings within
    // a session. Composing it inside the frame is what gives it the right owner and the right
    // context: Compose's DialogWindow takes its owner from LocalAwtWindow (provided by this
    // window's panel), so the dialog is document-modal to the main frame, stays above it and never
    // appears in the taskbar; and it inherits the strings, layout direction, theme and shortcut
    // labels the shell provides around [BlazeContent], so its own chrome matches the rest of the
    // app in light, dark and RTL.
    val settingsDialogState = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        width = AppMetrics.SettingsDialogWidth,
        height = AppMetrics.SettingsDialogHeight,
    )

    IslandWindow(
        host = shell,
        title = "Blaze",
        minimumSizePx = 760 to 480,
        windowIcon = { AppTrayIcon.windowIcon() },
        ambientGlowEnabled = appSettings.ambientGlowEnabled,
        strings = islandStrings,
        clipboard = islandClipboard,
        shortcutLabels = shortcutLabels,
        notifications = notifications,
        drop = drop,
        onPreviewKeyEvent = onPreviewKeyEvent,
        titleBar = { BlazeTitleBar(shell) },
        content = { BlazeContent() },
        contentOverlays = {
            // Rendered at the window root so it floats above every screen, exactly like the menu
            // popups; it reads all state from the shared controller and no-ops when closed.
            SearchEverywherePopup(searchController)
        },
        dialogs = {
            if (shell.settingsOpen.value) {
                SettingsDialogWindow(
                    state = settingsDialogState,
                    onDismiss = shell::closeSettings,
                )
            }

            // The security consent queue, rendered one dialog at a time (oldest first) above every
            // screen. It lives here - composed inside the frame and around the same providers as
            // the rest of the content - so the DialogWindow takes Blaze's frame as its owner
            // (document-modal, no taskbar entry) and the copy follows the app strings, theme and
            // RTL like everything else. The shell has already raised the frame when the request
            // arrived; answering (or the request being withdrawn) removes the dialog from the
            // composition and the suspended caller resumes on the service side.
            val consentService = koinInject<ConsentService>()
            val pendingConsent by consentService.pending.collectAsState()
            pendingConsent.firstOrNull()?.let { request ->
                ConsentDialogWindow(
                    request = request,
                    onDecision = { decision -> consentService.respond(request.id, decision) },
                )
            }
        },
    )
}
