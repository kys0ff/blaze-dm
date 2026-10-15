package org.island.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.island.foundation.CurrentIslandPlatform
import org.island.foundation.IslandPlatform
import org.island.theme.ui.IslandSemanticColors
import org.island.ui.menu.IslandMenu
import org.island.ui.menu.LocalMenuWidthGroup
import org.island.ui.menu.MenuEntry
import org.island.ui.menu.MenuWidthPreloader
import org.island.ui.menu.newMenuAnchor
import org.island.ui.menu.rememberMenuAnchor
import org.island.ui.menu.trackingModifier
import org.island.ui.text.LocalIslandUiStrings
import org.island.ui.toolbar.IslandToolbarIconButton
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * One title-bar menu header, described by its label and the popup entries it opens. A host app
 * supplies a list of these - Blaze maps its `TitleBarMenuCategory` entries to this shape, a demo
 * app can hand-craft a single "File" category - so the shell's expand/hover logic never has to
 * know the app's enum.
 *
 * The [entries] function is called both from the composition (to draw the popup) and from the
 * width preload (to measure it); the shell passes an empty `onExecuted` for the measurement pass
 * because only the drawn shape (labels, icons, accelerators) costs width. Hosts should wrap the
 * lambda in `remember(stateKeys)` so the shell's preload recomposition stays stable.
 */
class IslandMenuCategory(
    /** Header text drawn in the title-bar row when the menu is expanded. */
    val label: String,
    /** Builds this category's popup rows; fires [onExecuted] from every action's click handler. */
    val entries: (onExecuted: () -> Unit) -> List<MenuEntry>,
)

/**
 * The leading hamburger button / title-bar menu headers. Windows and Linux get it (their windows
 * have no native application menu); macOS keeps using the native bar, so this is a no-op there.
 * Clicking the hamburger button hides it and reveals the title-bar menu headers. Hovering across
 * headers switches the open menu; clicking a menu command or clicking outside closes the title-bar
 * menu items and restores the hamburger button.
 *
 * Every header's popup draws at one width - the width of the widest menu in the bar - measured up
 * front by [MenuWidthPreloader] into the app-wide [LocalMenuWidthGroup], exactly like IntelliJ's
 * menu bar, where the popups never resize as you slide between headers.
 *
 * @param categories the headers shown when the menu is expanded (at least one); each category
 *   carries its own popup builder so the shell does not need to know the app's enum or command
 *   vocabulary.
 * @param onExpandChange fires whenever the hamburger toggles between its collapsed button and
 *   its expanded header row - the host uses this to hide any siblings (e.g. a status widget) that
 *   should not compete with the menu headers for title-bar space.
 */
@Composable
fun IslandHamburgerMenu(
    categories: List<IslandMenuCategory>,
    onExpandChange: (Boolean) -> Unit,
) {
    if (CurrentIslandPlatform == IslandPlatform.MacOS) return
    if (categories.isEmpty()) return
    val strings = LocalIslandUiStrings.current
    var isExpanded by remember { mutableStateOf(false) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }

    // One width for the whole app. The lists are measured with a no-op executed handler: only the
    // drawn shape (labels, icons, accelerators) costs width, so the closures are irrelevant here.
    // Seeding the app-wide group here is what lets every title-bar popup open at the settled width
    // on its very first frame, before any menu has been hovered.
    val menuWidthGroup = LocalMenuWidthGroup.current
    val categoryMenus = remember(categories) {
        categories.map { it.entries {} }
    }
    MenuWidthPreloader(menus = categoryMenus, group = menuWidthGroup)

    // Anchors + header bounds are keyed on the integer index so IslandMenu's `Any`-typed
    // headerBounds/onHeaderHovered/onHeaderClicked contract stays generic - the shell never
    // inspects the key past converting it back to an index.
    val anchors = remember(categories) { categories.indices.map { newMenuAnchor() } }
    val headerBoundsMap = remember { mutableStateMapOf<Int, Rect>() }

    if (!isExpanded) {
        val anchor = rememberMenuAnchor()
        Box(modifier = anchor.trackingModifier) {
            IslandToolbarIconButton(
                key = AllIconsKeys.General.Menu,
                tooltip = strings.mainMenu(),
                onClick = {
                    isExpanded = true
                    activeIndex = 0
                    onExpandChange(isExpanded)
                },
            )
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((index, category) in categories.withIndex()) {
                val anchor = anchors[index]
                val isActive = activeIndex == index

                Box(
                    modifier = anchor.trackingModifier.onGloballyPositioned { coords ->
                        headerBoundsMap[index] = coords.boundsInRoot()
                    },
                ) {
                    val shape = RoundedCornerShape(4.dp)
                    Box(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (isActive) IslandSemanticColors.hover else Color.Transparent)
                            .clickable {
                                if (isActive) {
                                    isExpanded = false
                                    activeIndex = null
                                    onExpandChange(isExpanded)
                                } else {
                                    activeIndex = index
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = category.label,
                            style = JewelTheme.typography.small,
                            color = JewelTheme.globalColors.text.normal,
                            maxLines = 1,
                        )
                    }

                    if (isActive) {
                        IslandMenu(
                            entries = category.entries {
                                isExpanded = false
                                activeIndex = null
                                onExpandChange(isExpanded)
                            },
                            anchor = anchor,
                            onDismissRequest = {
                                isExpanded = false
                                activeIndex = null
                                onExpandChange(isExpanded)
                            },
                            headerBounds = headerBoundsMap,
                            onHeaderHovered = { hoveredIndex ->
                                if (hoveredIndex is Int && activeIndex != hoveredIndex) {
                                    activeIndex = hoveredIndex
                                }
                            },
                            onHeaderClicked = { clickedIndex ->
                                if (clickedIndex is Int) {
                                    if (activeIndex == clickedIndex) {
                                        isExpanded = false
                                        activeIndex = null
                                        onExpandChange(isExpanded)
                                    } else {
                                        activeIndex = clickedIndex
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
