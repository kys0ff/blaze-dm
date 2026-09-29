package org.blaze.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.blaze.presentation.theme.BlazeTheme

/**
 * The submenu contract, tested with a real pointer and real keys.
 *
 * This started life as the *reproduction* of the round-3 bug against Jewel's `PopupMenu`, where three
 * of these six cases failed: after the pointer travelled from an open submenu back into the parent
 * menu, the submenu stayed open, kept the highlight, and swallowed every pointer event in the scene —
 * the mouse had no way out and only the arrow keys could leave (the measurements behind that claim are
 * in the step-C report). Jewel drives submenu state purely from per-item hover, and hover never
 * arrives, so the trap is not fixable from outside its menu code.
 *
 * It now pins [BlazeMenu]'s behavior, which owns the pointer itself and therefore has to keep all the
 * IDE's escape routes: hovering a parent opens, travelling in keeps it open, travelling back closes it
 * and hands the highlight to the row under the cursor, another parent takes the submenu over, Left and
 * Escape peel one level, Right opens, Enter acts and closes, and the arrows wrap around skipping
 * separators and disabled rows.
 */
@OptIn(ExperimentalTestApi::class)
class MenuSubmenuHoverTest {

    /** Whether the menu is composed, plus the last row the user actually fired. */
    private class MenuHost {
        var open: Boolean by mutableStateOf(true)
        var fired: String? by mutableStateOf(null)

        /** A leaf handler that records it ran, so a test can tell "fired" from "merely highlighted". */
        fun clicks(label: String): () -> Unit = { fired = label }
    }

    /** The row context menu's shape: an item, a submenu, a separator, a leaf. */
    private fun menuEntries(host: MenuHost) = listOf(
        MenuEntry.Action("Open", onClick = host.clicks("Open")),
        MenuEntry.Submenu(
            label = "Priority",
            children = listOf(
                MenuEntry.Action("High", onClick = host.clicks("High")),
                MenuEntry.Action("Low", onClick = host.clicks("Low")),
            ),
        ),
        MenuEntry.Separator,
        MenuEntry.Action("Remove", onClick = host.clicks("Remove")),
    )

    private fun ComposeUiTest.showBlazeMenu(host: MenuHost, entries: List<MenuEntry>) {
        setContent {
            BlazeTheme(isDark = true) {
                // A real control holds the menu open, exactly as a row or a header button does, so
                // dismissing is observed through the same `open` flag the app reads.
                Box(Modifier.size(24.dp).padding(8.dp))
                if (host.open) {
                    BlazeMenu(
                        entries = entries,
                        anchor = rememberMenuAnchorAt(Offset(20f, 40f)),
                        onDismissRequest = { host.open = false },
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.showMenuWithSubmenu(host: MenuHost) =
        showBlazeMenu(host, menuEntries(host))

    // ── the mouse ─────────────────────────────────────────────────────────────

    @Test
    fun `hovering the parent of a submenu opens it`() = runDesktopComposeUiTest(width = 800, height = 600) {
        val host = MenuHost()
        showMenuWithSubmenu(host)

        hoverOn("Priority")

        assertTrue(exists("High"), "hovering a submenu parent must open it")
        assertTrue(host.open, "opening a submenu must not close the menu")
    }

    @Test
    fun `moving the pointer from the parent menu into the submenu keeps it open`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            hoverOn("Priority")
            hoverOn("High")

            assertTrue(exists("High"), "the submenu must survive the pointer travelling into it")
        }

    @Test
    fun `moving back into the parent menu closes the submenu and highlights the hovered item`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            hoverOn("Priority")
            hoverOn("High")
            // The trap: under Jewel this left the submenu open with the highlight inside it, and no
            // amount of mouse movement gave the parent menu back.
            hoverOn("Open")

            assertFalse(exists("High"), "leaving the submenu for another item must close it")
            assertTrue(host.open, "returning to the parent menu must not close the menu")
        }

    @Test
    fun `hovering a different submenu parent moves the open submenu to it`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showBlazeMenu(
                host,
                entries = listOf(
                    MenuEntry.Submenu("First parent", children = listOf(MenuEntry.Action("First child", onClick = {}))),
                    MenuEntry.Submenu("Second parent", children = listOf(MenuEntry.Action("Second child", onClick = {}))),
                ),
            )

            hoverOn("First parent")
            assertTrue(exists("First child"), "the first submenu must be open")

            hoverOn("Second parent")

            assertFalse(exists("First child"), "hovering another submenu parent must close the first")
            assertTrue(exists("Second child"), "hovering another submenu parent must open the second")
        }

    @Test
    fun `a click on a leaf fires it and closes every level`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            hoverOn("Priority")
            clickOn("High")

            assertEquals("High", host.fired, "the submenu row's own handler must run")
            assertFalse(host.open, "activating a leaf closes the whole menu")
        }

    // ── the keyboard ──────────────────────────────────────────────────────────

    @Test
    fun `the left arrow closes only the submenu, keeping the parent menu open`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            hoverOn("Priority")
            assertTrue(exists("High"))

            pressKeyOn("High", Key.DirectionLeft)

            assertFalse(exists("High"), "Left must close the submenu")
            assertTrue(exists("Priority"), "Left must not close the parent menu")
            assertTrue(host.open, "Left must not close the parent menu")
        }

    @Test
    fun `escape closes the submenu first and only then the whole menu`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            hoverOn("Priority")
            assertTrue(exists("High"))

            pressKeyOn("High", Key.Escape)
            assertFalse(exists("High"), "the first Escape closes the submenu")
            assertTrue(host.open, "the first Escape must leave the parent menu open")

            pressKeyOn("Open", Key.Escape)
            assertFalse(host.open, "the second Escape closes the whole menu")
        }

    @Test
    fun `the right arrow opens the submenu under the highlight and lands on its first row`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            // Walk onto the submenu's parent and push Right into it in one injection, so the hover
            // timer that a bare Down would start cannot fire between the two keys: this proves the
            // level opened because of Right, not because time passed.
            pressKeysOn("Open", listOf(Key.DirectionDown, Key.DirectionRight))

            assertTrue(exists("High"), "Right must open the submenu under the highlight")
            assertEquals("High", highlighted("High", "Low"), "Right must select the submenu's first row")
        }

    @Test
    fun `the arrows walk the rows, skip the separator and wrap around`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            // Start on the first row; Down goes to the submenu, the separator is skipped, the next
            // Down lands on Remove, and one more wraps back to the top.
            assertEquals("Open", highlighted("Open", "Priority", "Remove"), "the menu opens on its first row")

            pressKeyOn("Open", Key.DirectionDown)
            assertEquals("Priority", highlighted("Open", "Priority", "Remove"), "Down must move to the next row")

            pressKeyOn("Priority", Key.DirectionDown)
            assertEquals("Remove", highlighted("Open", "Priority", "Remove"), "Down must skip the separator")

            pressKeyOn("Remove", Key.DirectionDown)
            assertEquals("Open", highlighted("Open", "Priority", "Remove"), "Down must wrap from the last row")

            pressKeyOn("Open", Key.DirectionUp)
            assertEquals("Remove", highlighted("Open", "Priority", "Remove"), "Up must wrap the other way")
        }

    @Test
    fun `home and end jump to the first and last selectable rows`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            pressKeyOn("Open", Key.MoveEnd)
            assertEquals("Remove", highlighted("Open", "Priority", "Remove"), "End must land on the last real row")

            pressKeyOn("Remove", Key.MoveHome)
            assertEquals("Open", highlighted("Open", "Priority", "Remove"), "Home must land on the first real row")
        }

    @Test
    fun `enter fires the highlighted row and dismisses the menu`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showMenuWithSubmenu(host)

            pressKeyOn("Open", Key.Enter)

            assertEquals("Open", host.fired, "Enter must run the highlighted row")
            assertFalse(host.open, "Enter closes the menu after acting, like the IDE")
        }

    @Test
    fun `a disabled row is highlighted past, never fired`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val host = MenuHost()
            showBlazeMenu(
                host,
                entries = listOf(
                    MenuEntry.Action("First", onClick = host.clicks("First")),
                    MenuEntry.Action("Locked", enabled = false, onClick = host.clicks("Locked")),
                    MenuEntry.Action("Last", onClick = host.clicks("Last")),
                ),
            )

            pressKeyOn("First", Key.DirectionDown)
            assertEquals("Last", highlighted("First", "Locked", "Last"), "Down must skip a disabled row")

            pressKeyOn("Last", Key.DirectionUp)
            assertEquals("First", highlighted("First", "Locked", "Last"), "Up must skip it too")
        }

    // ── injection helpers ─────────────────────────────────────────────────────

    /**
     * Moves the pointer onto [text] and lets the submenu's hover delay elapse. The delay is part of
     * the contract (an accidental pass over a parent must not fling a submenu open), so the clock is
     * advanced explicitly instead of hoping the test clock auto-advances it.
     */
    private fun ComposeUiTest.hoverOn(text: String) {
        nodeWithText(text).performMouseInput { moveTo(center) }
        waitForIdle()
        mainClock.advanceTimeBy(HOVER_DELAY_MS)
        waitForIdle()
    }

    private fun ComposeUiTest.clickOn(text: String) {
        nodeWithText(text).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.pressKeyOn(text: String, key: Key) {
        nodeWithText(text).performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        waitForIdle()
    }

    /**
     * Injects several keys without letting the clock run between them. [Key] is a value class, so the
     * sequence arrives as a list rather than a vararg.
     */
    private fun ComposeUiTest.pressKeysOn(text: String, keys: List<Key>) {
        nodeWithText(text).performKeyInput {
            keys.forEach { key ->
                keyDown(key)
                keyUp(key)
            }
        }
        waitForIdle()
    }

    /**
     * Which of [candidates] the menu holds the highlight on. [BlazeMenu] publishes the highlighted row
     * through the standard `Selected` semantics, so this reads the same fact an assistive technology
     * (and a user looking at the hover fill) sees.
     */
    private fun ComposeUiTest.highlighted(vararg candidates: String): String? =
        candidates.firstOrNull { label ->
            onAllNodes(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

    /**
     * The one node labelled [text]. Menu labels are unique within a menu, but a submenu can be open
     * with its parent still composed, so this tolerates the duplicate the buggy state produced and
     * picks the first.
     */
    private fun ComposeUiTest.nodeWithText(text: String): SemanticsNodeInteraction =
        onAllNodesWithText(text).fetchSemanticsNodes().let { nodes ->
            check(nodes.isNotEmpty()) { "no node labelled '$text' is composed" }
            onAllNodesWithText(text)[0]
        }

    private fun ComposeUiTest.exists(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** A little past [300ms], which is the delay BlazeMenu waits before opening on hover. */
        const val HOVER_DELAY_MS = 450L
    }
}
