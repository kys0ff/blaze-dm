package org.island.ui.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure layout reducer, tested without a coroutine scope or a model: [reduceToolWindowLayout] is the
 * single place the shared dockable-window rules live, so the behaviors several panes used to duplicate
 * (WINDOW re-negotiating the split once, detaching clearing the placement, a free "Move To" re-anchoring,
 * a dragged split clamping) are asserted directly against the data. The name rehydrators are covered too,
 * since persisted store values are the compatibility contract that outlives any identifier move.
 */
class ToolWindowLayoutTest {

    private val base = ToolWindowLayout(
        visible = true,
        viewMode = ToolWindowViewMode.DOCK_PINNED,
        moveTo = ToolWindowMoveTo.RIGHT_TOP,
        splitFraction = 0.68f,
        innerSplitFraction = 0.50f,
        floatingOffsetX = 0f,
        floatingOffsetY = 0f,
    )

    @Test
    fun `visibility toggles and sets without disturbing the rest`() {
        val hidden = reduceToolWindowLayout(base, ToolWindowLayoutAction.SetVisible(false))
        assertEquals(false, hidden.visible)
        assertEquals(base.viewMode, hidden.viewMode)

        val toggled = reduceToolWindowLayout(base, ToolWindowLayoutAction.ToggleVisible)
        assertEquals(false, toggled.visible)
        assertEquals(true, reduceToolWindowLayout(toggled, ToolWindowLayoutAction.ToggleVisible).visible)
    }

    @Test
    fun `entering window mode claims the larger share once and never gives it back`() {
        // A list holding 68% yields down to the window share when the pane promises the larger half.
        val inWindow = reduceToolWindowLayout(base, ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.WINDOW))
        assertEquals(WINDOW_MODE_CONTENT_FRACTION, inWindow.splitFraction)

        // A split already friendlier to the pane is left alone: entering WINDOW never *shrinks* the pane.
        val generous = base.copy(splitFraction = 0.20f)
            .let { reduceToolWindowLayout(it, ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.WINDOW)) }
        assertEquals(0.20f, generous.splitFraction)

        // Leaving the mode keeps the share the pane grew to.
        val back = reduceToolWindowLayout(inWindow, ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.DOCK_PINNED))
        assertEquals(WINDOW_MODE_CONTENT_FRACTION, back.splitFraction)

        // Any non-window mode leaves the dragged split untouched.
        val float = reduceToolWindowLayout(base, ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.FLOAT))
        assertEquals(0.68f, float.splitFraction)
    }

    @Test
    fun `detaching a docked pane clears its placement but a hop between free modes keeps it`() {
        val placed = base.copy(floatingOffsetX = 300f, floatingOffsetY = 200f)

        // Docked -> floating: the layer is asked for a corner, so the stale placement is dropped.
        val detached = reduceToolWindowLayout(placed, ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.FLOAT))
        assertEquals(0f, detached.floatingOffsetX)
        assertEquals(0f, detached.floatingOffsetY)

        // Floating -> floating: the spot the window reported is preserved.
        val undocked = reduceToolWindowLayout(
            detached.copy(floatingOffsetX = 640f, floatingOffsetY = 20f),
            ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.UNDOCK),
        )
        assertEquals(640f, undocked.floatingOffsetX)

        // Floating -> docked: no clearing, the pane keeps its stored geometry.
        val redocked = reduceToolWindowLayout(
            undocked.copy(floatingOffsetX = 120f),
            ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.DOCK_PINNED),
        )
        assertEquals(120f, redocked.floatingOffsetX)
    }

    @Test
    fun `move-to re-anchors a free window but leaves a docked pane's geometry alone`() {
        val docked = base.copy(floatingOffsetX = 120f)
        val movedDocked = reduceToolWindowLayout(docked, ToolWindowLayoutAction.SetMoveTo(ToolWindowMoveTo.BOTTOM_LEFT))
        assertEquals(ToolWindowMoveTo.BOTTOM_LEFT, movedDocked.moveTo)
        assertEquals(120f, movedDocked.floatingOffsetX, "a docked pane keeps its stored window geometry")

        val free = docked.copy(viewMode = ToolWindowViewMode.FLOAT)
        val movedFree = reduceToolWindowLayout(free, ToolWindowLayoutAction.SetMoveTo(ToolWindowMoveTo.LEFT_TOP))
        assertEquals(ToolWindowMoveTo.LEFT_TOP, movedFree.moveTo)
        assertEquals(0f, movedFree.floatingOffsetX, "a free window is re-anchored, not left where it was")
    }

    @Test
    fun `split fractions clamp to the usable band`() {
        assertEquals(
            MAX_SPLIT_FRACTION,
            reduceToolWindowLayout(base, ToolWindowLayoutAction.SetSplitFraction(0.99f)).splitFraction,
        )
        assertEquals(
            MIN_SPLIT_FRACTION,
            reduceToolWindowLayout(base, ToolWindowLayoutAction.SetSplitFraction(0.01f)).splitFraction,
        )
        assertEquals(
            MAX_SPLIT_FRACTION,
            reduceToolWindowLayout(base, ToolWindowLayoutAction.SetInnerSplitFraction(1.5f)).innerSplitFraction,
        )
        assertEquals(0.42f, reduceToolWindowLayout(base, ToolWindowLayoutAction.SetSplitFraction(0.42f)).splitFraction)
    }

    @Test
    fun `floating bounds are written through verbatim`() {
        val moved = reduceToolWindowLayout(base, ToolWindowLayoutAction.SetFloatingBounds(64f, 128f, 500f, 400f))
        assertEquals(64f, moved.floatingOffsetX)
        assertEquals(128f, moved.floatingOffsetY)
        assertEquals(500f, moved.floatingWidthDp)
        assertEquals(400f, moved.floatingHeightDp)
    }

    @Test
    fun `persisted names round-trip and stale values fall back to defaults`() {
        // Every entry name survives the round trip, so a Kotlin identifier move cannot corrupt a store.
        ToolWindowViewMode.entries.forEach { mode ->
            assertEquals(mode, toolWindowViewModeFromName(mode.name))
        }
        ToolWindowMoveTo.entries.forEach { position ->
            assertEquals(position, toolWindowMoveToFromName(position.name))
        }

        assertEquals(ToolWindowViewMode.DOCK_PINNED, toolWindowViewModeFromName("NOT_A_MODE"))
        assertEquals(ToolWindowMoveTo.RIGHT_TOP, toolWindowMoveToFromName("NOT_A_POSITION"))
    }
}
