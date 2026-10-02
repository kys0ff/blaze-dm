package org.blaze.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * The one-width contract of the IDE's menu bar, pinned for [MenuWidthGroup].
 *
 * IntelliJ opens File, Edit, View and the rest at a single width — the width of the widest menu in
 * the bar — so the popups never resize as the pointer slides between headers. Blaze reaches that by
 * measuring every menu of a group up front ([MenuWidthPreloader], zero-size intrinsic pass) and
 * clamping each popup's root level to the shared width ([BlazeMenu.rootWidthGroup]).
 *
 * These tests check both halves: the group really takes the *maximum* across its menus (not the
 * first one measured), and a narrow menu handed a group opens strictly wider than the same menu
 * without one, at exactly the group's width.
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

    /** Composes a preloader over [menus] and one [BlazeMenu] of [menu], and returns the row width and group. */
    private fun ComposeUiTest.showPreloadedMenu(
        menus: List<List<MenuEntry>>,
        menu: List<MenuEntry>,
        useGroup: Boolean,
    ): Pair<Float, Float> {
        var capturedGroup: MenuWidthGroup? = null
        setContent {
            BlazeTheme(isDark = true) {
                val group = rememberMenuWidthGroup()
                capturedGroup = group
                MenuWidthPreloader(menus = menus, group = group)
                Box(Modifier.size(24.dp).padding(8.dp))
                BlazeMenu(
                    entries = menu,
                    anchor = rememberMenuAnchorAt(Offset(20f, 40f)),
                    onDismissRequest = {},
                    rootWidthGroup = if (useGroup) group else null,
                )
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
            val (_, narrowOnlyWidth) = showPreloadedMenu(listOf(narrowMenu), narrowMenu, useGroup = true)
            val (_, groupWidth) = showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, useGroup = true)

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
            val (unclampedRowWidth, _) = showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, useGroup = false)
            val (clampedRowWidth, groupWidth) = showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, useGroup = true)

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
    fun `a menu without a group keeps its own natural width`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val (rowWidth, groupWidth) = showPreloadedMenu(listOf(narrowMenu, wideMenu), narrowMenu, useGroup = false)

            assertTrue(groupWidth > rowWidth, "without the group the narrow menu must stay narrow")
        }
}
