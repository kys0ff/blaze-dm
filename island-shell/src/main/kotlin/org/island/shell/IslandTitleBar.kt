package org.island.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.island.theme.ui.islandAmbientFrame
import org.island.ui.menu.rememberMenuAnchor
import org.island.ui.menu.trackingModifier
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.newFullscreenControls
import org.jetbrains.jewel.window.utils.clientRegion

/**
 * The shell's Jewel title bar. Draws the standard flat frame color with the ambient glow anchored
 * at its top-left corner, and lays out an IDE-style leading cluster through [TitleBarScope.align]:
 *
 *  - The brand affordance (typically the app's [brand] icon, doubling as the OS system-menu
 *    button);
 *  - The app's hamburger title-bar menu - a list of [IslandMenuCategory] rows the host supplies;
 *  - Any additional [leadingCollapsedContent] - widgets like a status pill that must yield space
 *    to the expanded header row, so the shell hides them automatically while the menu is open.
 *
 * End-aligned content (window controls, search/settings icons) is emitted by Jewel itself; a host
 * that wants extra trailing controls uses [TitleBarScope.align] with `Alignment.End` around this
 * composable's own returned scope.
 *
 * All Blaze-specific behavior (search toggle, settings gear, speed-limit widget) stays in the host
 * app and is handed to the shell through these slots.
 */
@Composable
fun DecoratedWindowScope.IslandTitleBar(
    host: IslandWindowHost,
    brand: @Composable () -> Unit,
    menuCategories: List<IslandMenuCategory>,
    leadingCollapsedContent: @Composable RowScope.() -> Unit = {},
) {
    TitleBar(
        modifier = Modifier
            .islandAmbientFrame(anchorY = 0.dp)
            .newFullscreenControls(),
    ) {
        var isMenuExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .align(Alignment.Start)
                .height(40.dp)
                .padding(start = 8.dp)
                .clientRegion("title_bar_left"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IslandAppIconButton(host = host, brand = brand)

            IslandHamburgerMenu(
                categories = menuCategories,
                onExpandChange = { isMenuExpanded = it },
            )

            if (!isMenuExpanded) leadingCollapsedContent()
        }
    }
}

/**
 * The brand icon doubles as the window's system-menu button.
 * Both left and right clicks open the same [IslandWindowSystemMenu].
 *
 * Clicks are consumed so they do not initiate window dragging. Hover and pressed states provide
 * normal mouse feedback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IslandAppIconButton(
    host: IslandWindowHost,
    brand: @Composable () -> Unit,
) {
    val anchor = rememberMenuAnchor()
    var menuOpen by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    // The menu's Popup is emitted as a layout child of whatever container surrounds it, and the
    // title-bar Row's `spacedBy` adds a gap in front of every child - including that zero-width
    // node. Without this own Box, opening the menu would inject an extra gap and shove the widgets
    // to the right. Wrapping keeps the Popup a child of this Box (which stacks, no inter-child
    // spacing), so the Row always sees exactly one child here, menu open or not.
    Box {
        Box(
            modifier = anchor.trackingModifier
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)

                            when (event.type) {
                                PointerEventType.Enter -> hovered = true
                                PointerEventType.Exit -> {
                                    hovered = false
                                    pressed = false
                                }

                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var pressedButton: PointerButton? = null
                        var validClick = true

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)

                            when (event.type) {
                                PointerEventType.Press -> {
                                    pressedButton = event.button

                                    if (
                                        event.button == PointerButton.Primary ||
                                        event.button == PointerButton.Secondary
                                    ) {
                                        pressed = true
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        validClick = false
                                    }
                                }

                                PointerEventType.Release -> {
                                    val button = pressedButton ?: event.button

                                    event.changes.forEach { it.consume() }
                                    pressed = false

                                    if (
                                        validClick &&
                                        (
                                                button == PointerButton.Primary ||
                                                        button == PointerButton.Secondary
                                                )
                                    ) {
                                        menuOpen = true
                                    }

                                    break
                                }

                                PointerEventType.Exit -> {
                                    pressed = false
                                    break
                                }

                                else -> Unit
                            }
                        }
                    }
                }
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                brand()
            }
        }

        if (menuOpen) {
            IslandWindowSystemMenu(
                host = host,
                anchor = anchor,
                onDismissRequest = { menuOpen = false },
            )
        }
    }
}
