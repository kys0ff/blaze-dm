package org.blaze.presentation.screens.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure multi-select rules the whole UI agrees on: click selects, Ctrl/Cmd toggles, Shift extends a
 * contiguous span from the anchor, the arrows move (and Shift+arrow grows) the selection, "Select All"
 * takes every visible row and "Clear" empties it. Kept free of Compose so the behaviour is directly
 * testable against a fixed visible order.
 */
class SelectionReducerTest {

    private val visible = listOf("a", "b", "c", "d")

    private fun ids(s: Selection) = s.ids

    @Test
    fun `a plain click selects only that row and sets the anchor`() {
        val start = Selection(setOf("x"), "x", "x")
        val after = reduceSelection(start, visible, SelectionAction.Click("c"))

        assertEquals(setOf("c"), ids(after))
        assertEquals("c", after.anchor)
        assertEquals("c", after.cursor)
    }

    @Test
    fun `ctrl-click adds a row to the selection without dropping the others`() {
        val start = reduceSelection(Selection(), visible, SelectionAction.Click("a"))
        val after = reduceSelection(start, visible, SelectionAction.Click("c", additive = true))

        assertEquals(setOf("a", "c"), ids(after))
        assertEquals("c", after.anchor)
    }

    @Test
    fun `ctrl-click on an already-selected row removes just that row`() {
        val start = Selection(ids = setOf("a", "b", "c"), anchor = "a", cursor = "c")
        val after = reduceSelection(start, visible, SelectionAction.Click("b", additive = true))

        assertEquals(setOf("a", "c"), ids(after))
        assertFalse("b" in after.ids)
    }

    @Test
    fun `shift-click selects the contiguous span from the anchor`() {
        // Anchor on "a", then Shift+Click "d": everything between (in visible order) is selected.
        val start = reduceSelection(Selection(), visible, SelectionAction.Click("a"))
        val after = reduceSelection(start, visible, SelectionAction.Click("d", range = true))

        assertEquals(setOf("a", "b", "c", "d"), ids(after))
        // The anchor stays put; the cursor moves to the clicked end.
        assertEquals("a", after.anchor)
        assertEquals("d", after.cursor)
    }

    @Test
    fun `shift-click spans backwards too`() {
        val start = reduceSelection(Selection(), visible, SelectionAction.Click("d"))
        val after = reduceSelection(start, visible, SelectionAction.Click("b", range = true))

        assertEquals(setOf("b", "c", "d"), ids(after))
        assertEquals("d", after.anchor)
    }

    @Test
    fun `select all takes every visible row`() {
        val after = reduceSelection(Selection(), visible, SelectionAction.SelectAll)

        assertEquals(visible.toSet(), ids(after))
        assertEquals("a", after.anchor)
        assertEquals("d", after.cursor)
    }

    @Test
    fun `clear empties the selection`() {
        val start = Selection(setOf("a", "b"), "a", "b")
        val after = reduceSelection(start, visible, SelectionAction.Clear)

        assertTrue(after.isEmpty)
    }

    @Test
    fun `arrow down from nothing selects the first row, then walks the list`() {
        val first = reduceSelection(Selection(), visible, SelectionAction.Move(1))
        assertEquals(setOf("a"), ids(first))

        val next = reduceSelection(first, visible, SelectionAction.Move(1))
        assertEquals(setOf("b"), ids(next))
    }

    @Test
    fun `arrow movement clamps at both ends`() {
        val atTop = reduceSelection(Selection(), visible, SelectionAction.Move(-1))
        assertEquals(setOf("a"), ids(atTop)) // already at the top, cannot go above

        val atBottom = reduceSelection(Selection(setOf("d"), "d", "d"), visible, SelectionAction.Move(1))
        assertEquals(setOf("d"), ids(atBottom))
    }

    @Test
    fun `shift-arrow extends the span from the anchor while the cursor moves`() {
        val start = reduceSelection(Selection(), visible, SelectionAction.Click("a"))
        val extended = reduceSelection(start, visible, SelectionAction.Move(2, extend = true))

        assertEquals(setOf("a", "b", "c"), ids(extended))
        assertEquals("a", extended.anchor)
        assertEquals("c", extended.cursor)
    }

    @Test
    fun `shift-arrow back shrinks the span but keeps the anchor`() {
        // A span anchored on "d" with the cursor at "d"; Shift+Up pulls the cursor back to "c",
        // shrinking the selected span while the anchor stays put.
        val start = Selection(ids = setOf("b", "c", "d"), anchor = "d", cursor = "d")
        val after = reduceSelection(start, visible, SelectionAction.Move(-1, extend = true))

        assertEquals(setOf("c", "d"), ids(after))
        assertEquals("d", after.anchor)
        assertEquals("c", after.cursor)
    }

    @Test
    fun `an empty visible list always reduces to nothing`() {
        val after = reduceSelection(Selection(setOf("a"), "a", "a"), emptyList(), SelectionAction.Click("a"))
        assertTrue(after.isEmpty)
    }

    // ── allVisibleSelected: the rule behind the Select All / Deselect label pair ──

    @Test
    fun `select all makes the table fully selected, and deselecting ends it`() {
        val all = reduceSelection(Selection(), visible, SelectionAction.SelectAll)
        assertTrue(allVisibleSelected(visible, all.ids))

        val cleared = reduceSelection(all, visible, SelectionAction.Clear)
        assertFalse(allVisibleSelected(visible, cleared.ids))
    }

    @Test
    fun `a partial selection is never 'all selected'`() {
        val partial = reduceSelection(Selection(), visible, SelectionAction.Click("b"))
        assertFalse(allVisibleSelected(visible, partial.ids))
    }

    @Test
    fun `an empty table is never 'all selected' - the toggle must not read as Deselect`() {
        assertFalse(allVisibleSelected(emptyList(), emptySet()))
    }

    @Test
    fun `only the rows currently shown decide the label`() {
        // The user filtered the list down to "a" and "b" and selected those: Select All is spent, even
        // though rows hidden by the filter are unselected.
        assertTrue(allVisibleSelected(listOf("a", "b"), setOf("a", "b", "c")))
        assertFalse(allVisibleSelected(listOf("a", "b", "c"), setOf("a", "b")))
    }
}
