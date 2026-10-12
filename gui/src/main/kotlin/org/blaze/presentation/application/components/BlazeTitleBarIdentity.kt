package org.blaze.presentation.application.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.application.AppShell
import org.blaze.presentation.components.rememberMenuAnchor
import org.blaze.presentation.components.trackingModifier
import org.blaze.presentation.icons.BlazeFlame
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.utils.clientRegion
import org.koin.compose.koinInject

/**
 * The IDE's title-bar identity and action clusters, laid out through [TitleBarScope.align]:
 *
 * - Start: a hamburger main-menu affordance (Windows/Linux only — macOS keeps its native bar), then
 *   the app icon and name in a medium weight (never bold), then the one real, backed IDE widget (the
 *   Snail-Mode speed selector). The bar itself is a flat frame color (see BlazeTheme's title-bar
 *   style) — no ambient gradient, no trailing divider.
 * - End: the search and settings icons. Jewel places end-aligned content to the left of the
 *   auto-emitted window controls, so the order reads search, gear, then controls.
 *
 * Both clusters live inside the window's composition-local providers (see [BlazeTitleBar]), so the
 * tooltips and menu rows resolve the same OS-formatted accelerators as the rest of the chrome.
 */
@Composable
fun TitleBarScope.BlazeTitleBarIdentity(shell: AppShell) {
    val commandBus = koinInject<AppCommandBus>()
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
        WindowIconButton(shell)

        HamburgerMenuButton(commandBus) { isMenuExpanded = it }

        if (!isMenuExpanded) SpeedLimitWidget()
    }
}

/**
 * The app icon doubles as the window's system-menu button.
 * Both left and right clicks open the same [WindowSystemMenu].
 *
 * Clicks are consumed so they do not initiate window dragging.
 * Hover and pressed states provide normal mouse feedback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowIconButton(shell: AppShell) {
    val anchor = rememberMenuAnchor()
    var menuOpen by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = anchor.trackingModifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)

                        when (event.type) {
                            PointerEventType.Enter -> {
                                hovered = true
                            }

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
                                validClick = false
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
        Icon(
            imageVector = BlazeFlame,
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )
    }

    if (menuOpen) {
        WindowSystemMenu(
            shell = shell,
            anchor = anchor,
            onDismissRequest = {
                menuOpen = false
            },
        )
    }
}
