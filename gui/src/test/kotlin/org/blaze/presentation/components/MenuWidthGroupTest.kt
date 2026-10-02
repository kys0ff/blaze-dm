package org.blaze.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.blaze.presentation.theme.BlazeTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The one-width contract of the IDE's menu bar, pinned for [MenuWidthGroup] and [LocalMenuWidthGroup].
 *
 * IntelliJ opens every popup — menu-bar or context — at one shared width, the widest menu's. Blaze
 * reaches that app-wide: [BlazeMenu] joins the [LocalMenuWidthGroup] by default, clamping its root
 * level to the group and reporting its own drawn width back so the group only grows; the title bar
 * additionally pre-measures its five menus with [MenuWidthPreloader] so their popups settle before
 * the first one is even shown.
 *
 * These tests pin the halves: the preloader takes the *maximum* (not the first menu measured), a
 * unifying menu opens at the shared width instead of its own, an opted-out menu keeps its natural
 * width, and simply opening a wide menu raises the group for everything that follows.
 */
@OptIn(ExperimentalTestApi::class)
class MenuWidthGroupTest {

    private val narrowMenu = listOf(
        MenuEntry.Action("Cut", onClick = {}),
    )

    private val wideMenu = listOf(
        MenuEntry.Action("Search Everywhere", accelerator = "Double Shift", onClick = {}),
        MenuEntry.Action("Pause All Downloads", onClick = {}),
    )

    /**
     * Composes the app group over a [MenuWidthPreloader] of [menus] and one [BlazeMenu] of [menu],
     * unifying it unless [unifyWidth] is false; returns the popup's row width and the group width.
     */
    private fun ComposeUiTest.showPreloadedMenu(
        menus: List<List<MenuEntry>>,
        menu: List<MenuEntry>,
        unifyWidth: Boolean,
    ): Pair<Float, Float> {
        var capturedGroup: MenuWidthGroup? = null
        setContent {
            BlazeTheme(isDark = true) {
                val group = rememberMenuWidthGroup()
                capturedGroup = group
                CompositionLocalProvider(LocalMenuWidthGroup provides group) {
                    MenuWidthPreloader(menus = menus, group = group)
                    Box(Modifier.size(24.dp).padding(8.dp))
                    BlazeMenu(
                        entries = menu,
                        anchor = rememberMenuAnchorAt(Offset(20f, 40f)),
                        onDismissRequest = {},
                        unifyWidth = unifyWidth,
                    )
                }
            }
        }
        waitForIdle()
        // The preloader's measured rows also carry the label, as never-placed zero-size semantics
        // nodes, so the popup's real row is the widest match rather than the first.
        val rowWidthPx = onAllNodes(hasText(menu.first().label))
            .fetchSemanticsNodes()
            .maxOf { it.size.width }
            .toFloat()
        // The group is read after idle, not captured during composition: the preloader writes its
        // width in the layout pass, which is after the frame's composition already ran.
        return rowWidthPx to checkNotNull(capturedGroup).widthPx
    }

    @Test
    fun `the preloader raises the group to the widest menu of the group`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val (_, narrowOnlyWidth) = showPreloadedMenu(listOf(narrowMenu), narrowMenu, unifyWidth = true)
            val (_, groupWidth) = showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, unifyWidth = true)

            assertTrue(narrowOnlyWidth > 0f, "the preloader must measure something")
            assertTrue(
                groupWidth > narrowOnlyWidth,
                "the group must hold the widest menu, not the first one measured " +
                    "(widest=$groupWidth vs narrow-only=$narrowOnlyWidth)",
            )
        }

    @Test
    fun `a menu sharing the group opens at the shared width, not its own`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val (unclampedRowWidth, _) =
                showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, unifyWidth = false)
            val (clampedRowWidth, groupWidth) =
                showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, unifyWidth = true)

            assertTrue(
                clampedRowWidth > unclampedRowWidth,
                "the narrow menu must be widened by the group (was $unclampedRowWidth, became $clampedRowWidth)",
            )
            assertTrue(
                clampedRowWidth >= groupWidth - 2f,
                "the popup's rows must reach the group width (rows=$clampedRowWidth, group=$groupWidth)",
            )
        }

    @Test
    fun `a menu that opts out keeps its own natural width`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val (rowWidth, groupWidth) =
                showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, unifyWidth = false)

            assertTrue(groupWidth > rowWidth, "without unification the narrow menu must stay narrow")
        }

    @Test
    fun `opening a wide menu raises the group for every menu that follows`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            // Nothing preloaded: the group can only grow from the width the open popup actually drew.
            val (rowWidth, groupWidth) = showPreloadedMenu(emptyList(), wideMenu, unifyWidth = true)

            assertTrue(groupWidth > 0f, "an opened menu must report its width into the group")
            assertTrue(
                groupWidth in rowWidth - 2f..rowWidth + 2f,
                "the group must settle at exactly the width the menu drew (group=$groupWidth, row=$rowWidth)",
            )
        }
}
