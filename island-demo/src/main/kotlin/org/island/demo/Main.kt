package org.island.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import org.island.shell.IslandMenuCategory
import org.island.shell.IslandTitleBar
import org.island.shell.IslandWindow
import org.island.shell.IslandWindowHost
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTheme
import org.island.theme.ui.ShortcutLabels
import org.island.ui.IslandPanel
import org.island.ui.menu.MenuEntry
import org.island.ui.notifications.NotificationsState
import org.island.ui.text.IslandUiStrings
import org.jetbrains.jewel.ui.component.Text

/**
 * A ~150-line Islands app that boots directly on top of the island-* modules and nothing else.
 * Runs as `./gradlew :island-demo:run` and proves the foundation is genuinely app-agnostic: no
 * Blaze import, no Koin graph, no i18n bundle, no download engine, no os-integration parser.
 *
 * The demo wires the four island layers together exactly like Blaze's own shell does:
 *
 *  - [IslandTheme] installs the Islands chrome + the Jewel IntUi theme around the whole window;
 *  - [IslandWindow] owns the frame: Jewel's `DecoratedWindow`, the DWM caption on Windows, the
 *    tray-hide / ICONIFIED folding, the whole-window drop plumbing (unused here - the demo has no
 *    parser so it stays on the default no-drop slot), and the CompositionLocal set every island
 *    component reads;
 *  - [IslandTitleBar] draws the flat chrome and mounts the app's menu (the shell's hamburger +
 *    per-category popup system) - the demo hands it a single "Demo" category so the same code
 *    path Blaze uses is exercised without pulling in a command bus;
 *  - [IslandPanel] paints the two body surfaces so the "islands floating on the frame" idiom is
 *    visible without any Blaze chrome;
 *  - Local notifications come through the [NotificationsState] the shell installs at the window
 *    root - the menu's "Say hello" / "About this demo" rows fire one balloon each, so the
 *    [org.island.ui.notifications.NotificationHost] the shell mounts has something to draw.
 *
 * Everything app-specific lives in slots the host fills; nothing in this file reaches into an
 * island module's internals, so a new app can start here and grow.
 */
fun main() = application {
    IslandDemoApplication()
}

@Composable
private fun ApplicationScope.IslandDemoApplication() {
    val windowState = remember { WindowState(size = DpSize(900.dp, 620.dp)) }
    val visible = remember { mutableStateOf(true) }
    val raiseToken = remember { mutableStateOf(0L) }
    val notifications = remember { NotificationsState() }
    val shell = remember(windowState, visible, raiseToken) {
        DemoWindowHost(windowState, visible, raiseToken, this::exitApplication)
    }

    var counter by remember { mutableLongStateOf(0L) }
    // A single "Demo" category with the three rows the shell's popup renderer cares about: two
    // plain actions that fire balloons, and a reset that writes through to the body's counter.
    // The categories are remembered against [notifications] so a shell restart (which recreates
    // the state) rebuilds the closures; the counter is read through the delegate each click.
    val menuCategories = remember(notifications) {
        listOf(
            IslandMenuCategory(
                label = "Demo",
                entries = { onExecuted ->
                    listOf(
                        MenuEntry.Action(
                            label = "Say hello",
                            onClick = {
                                onExecuted()
                                notifications.info("Hello from the Island Demo shell.")
                            },
                        ),
                        MenuEntry.Action(
                            label = "About this demo",
                            onClick = {
                                onExecuted()
                                notifications.info(
                                    message = "Built on island-foundation + island-theme-api " +
                                            "+ island-theme-ui + island-ui + island-shell.",
                                )
                            },
                        ),
                        MenuEntry.Separator,
                        MenuEntry.Action(
                            label = "Reset counter",
                            onClick = {
                                onExecuted()
                                counter = 0L
                            },
                        ),
                    )
                },
            ),
        )
    }

    IslandTheme(isDark = true) {
        IslandWindow(
            host = shell,
            title = "Island Demo",
            shortcutLabels = ShortcutLabels.None,
            minimumSizePx = 600 to 400,
            strings = IslandUiStrings.Default,
            notifications = notifications,
            titleBar = {
                IslandTitleBar(
                    host = shell,
                    // A solid accent disc as the brand mark - no icon dependency needed to prove
                    // the shell's brand + system-menu slot works. A host with a real product icon
                    // would draw its ImageVector / Swing.Icon here instead.
                    brand = {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(IslandSemanticColors.accent, CircleShape),
                        )
                    },
                    menuCategories = menuCategories,
                )
            },
            content = {
                DemoBody(
                    counter = counter,
                    onIncrement = { counter += 1 },
                    onDecrement = { if (counter > 0) counter -= 1 },
                )
            },
        )
    }
}

@Composable
private fun DemoBody(counter: Long, onIncrement: () -> Unit, onDecrement: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IslandPanel(modifier = Modifier.weight(1f).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Counter island")
                Text("$counter")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DemoPill(label = "Increment", onClick = onIncrement)
                    DemoPill(label = "Decrement", onClick = onDecrement)
                }
            }
        }
        IslandPanel(modifier = Modifier.weight(1f).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("What the shell already gives you")
                listOf(
                    "Flat frame + ambient glow (Modifier.islandAmbientFrame)",
                    "Native DWM caption on Windows; silent no-op elsewhere",
                    "Tray hide / ICONIFIED folding on Linux",
                    "Whole-window drop plumbing (rememberIslandWindowDrop)",
                    "Search-everywhere framework, tool windows, balloon notifications",
                    "Injectable IslandUiStrings - this app never wired an i18n bundle",
                ).forEach { line -> Text("- $line") }
            }
        }
    }
}

/** A minimal pill button, drawn from Compose primitives only, so the demo never leans on a
 *  specific Jewel button flavour (that would be a `gui`-style coupling this app is trying to avoid). */
@Composable
private fun DemoPill(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(IslandSemanticColors.accent.copy(alpha = 0.20f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label)
    }
}

/**
 * The minimum host an app has to supply: window state, three callbacks for the shell's lifecycle,
 * and an exit path. Blaze's `AppShell` implements the same interface but folds minimize into a
 * tray-hide; the demo just hides the frame directly.
 */
private class DemoWindowHost(
    override val windowState: WindowState,
    override val windowVisible: MutableState<Boolean>,
    override val windowRaiseToken: MutableState<Long>,
    private val exitApplication: () -> Unit,
) : IslandWindowHost {
    private var normalSize: DpSize? = null

    override fun onCloseRequest() = exitApplication()

    override fun onMinimize() {
        windowVisible.value = false
    }

    override fun isMaximized(): Boolean =
        windowState.placement == WindowPlacement.Maximized

    override fun restoreWindow() {
        if (!isMaximized()) return
        normalSize?.let { windowState.size = it }
        windowState.placement = WindowPlacement.Floating
    }

    override fun maximizeWindow() {
        if (isMaximized()) return
        normalSize = windowState.size
        windowState.placement = WindowPlacement.Maximized
    }
}
