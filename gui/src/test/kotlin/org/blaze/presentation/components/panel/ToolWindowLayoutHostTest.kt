package org.blaze.presentation.components.panel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowLayoutHostTest {

    private val tempDir = Files.createTempDirectory("blaze-tool-window-host-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `tool window spec holds view layout properties and bounds`() {
        var movedTo: ToolWindowMoveTo? = null
        var innerFraction: Float? = null

        val spec = ToolWindowSpec(
            id = "test_panel",
            title = "Test Panel",
            visible = true,
            viewMode = ToolWindowViewMode.DOCK_PINNED,
            moveTo = ToolWindowMoveTo.RIGHT_TOP,
            splitFraction = 0.65f,
            innerSplitFraction = 0.50f,
            floatingOffset = Offset(100f, 150f),
            floatingSize = DpSize(400.dp, 500.dp),
            onMoveTo = { movedTo = it },
            onInnerSplitFractionChange = { innerFraction = it },
            content = {}
        )

        assertEquals("test_panel", spec.id)
        assertEquals("Test Panel", spec.title)
        assertEquals(ToolWindowViewMode.DOCK_PINNED, spec.viewMode)
        assertEquals(ToolWindowMoveTo.RIGHT_TOP, spec.moveTo)

        spec.onMoveTo(ToolWindowMoveTo.LEFT_BOTTOM)
        assertEquals(ToolWindowMoveTo.LEFT_BOTTOM, movedTo)

        spec.onInnerSplitFractionChange(0.40f)
        assertEquals(0.40f, innerFraction)
    }

    @Test
    fun `server log model updates and persists floating bounds and inner split fraction`() = runBlocking {
        val repository = AppSettingsRepository(tempDir)
        val model = ServerLogWindowModel(repository)

        model.onLayoutEvent(ToolWindowLayoutAction.SetInnerSplitFraction(0.35f))
        model.onLayoutEvent(ToolWindowLayoutAction.SetFloatingBounds(120f, 200f, 500f, 400f))

        val live = model.state.value
        assertEquals(0.35f, live.innerSplitFraction)
        assertEquals(120f, live.floatingOffsetX)
        assertEquals(200f, live.floatingOffsetY)
        assertEquals(500f, live.floatingWidthDp)
        assertEquals(400f, live.floatingHeightDp)

        Thread.sleep(400)
        val saved = repository.settings.value
        assertEquals(0.35f, saved.serverLogInnerSplitFraction)
        assertEquals(120f, saved.serverLogFloatingOffsetX)
        assertEquals(200f, saved.serverLogFloatingOffsetY)
        assertEquals(500f, saved.serverLogFloatingWidthDp)
        assertEquals(400f, saved.serverLogFloatingHeightDp)
    }

    @Test
    fun `dock regions categorize panels by position correctly`() {
        val p1 = spec("p1", viewMode = ToolWindowViewMode.DOCK_PINNED, moveTo = ToolWindowMoveTo.RIGHT_TOP)
        val p2 = spec("p2", viewMode = ToolWindowViewMode.DOCK_PINNED, moveTo = ToolWindowMoveTo.RIGHT_BOTTOM)
        val p3 = spec("p3", viewMode = ToolWindowViewMode.FLOAT, moveTo = ToolWindowMoveTo.BOTTOM_RIGHT)

        val panels = listOf(p1, p2, p3)
        val docked = panels.filter { it.visible && it.viewMode.isDocked }
        val overlay = panels.filter { it.visible && it.viewMode.overlaysContent }

        assertEquals(2, docked.size)
        assertEquals(1, overlay.size)
        assertEquals("p3", overlay.first().id)

        val rightDocked = docked.regionFor(ToolWindowMoveTo.RIGHT_TOP, ToolWindowMoveTo.RIGHT_BOTTOM)
        assertEquals(listOf("p1", "p2"), rightDocked.map { it.id }, "the region's corner pane stacks first")
        assertTrue(rightDocked.contains(p1))
        assertTrue(rightDocked.contains(p2))
    }

    @Test
    fun `changing panel position to bottom left and bottom right sorts and reverses slot order`() {
        val details = spec("details", moveTo = ToolWindowMoveTo.BOTTOM_RIGHT)
        val log = spec("log", moveTo = ToolWindowMoveTo.BOTTOM_LEFT)

        val bottomPanels = listOf(details, log).regionFor(ToolWindowMoveTo.BOTTOM_LEFT, ToolWindowMoveTo.BOTTOM_RIGHT)
        assertEquals("log", bottomPanels[0].id, "BOTTOM_LEFT panel is first (left slot)")
        assertEquals("details", bottomPanels[1].id, "BOTTOM_RIGHT panel is second (right slot)")

        // User swaps their positions: details -> BOTTOM_LEFT, log -> BOTTOM_RIGHT
        val reversedPanels =
            listOf(details.copy(moveTo = ToolWindowMoveTo.BOTTOM_LEFT), log.copy(moveTo = ToolWindowMoveTo.BOTTOM_RIGHT))
                .regionFor(ToolWindowMoveTo.BOTTOM_LEFT, ToolWindowMoveTo.BOTTOM_RIGHT)

        assertEquals("details", reversedPanels[0].id, "details is now first (left slot)")
        assertEquals("log", reversedPanels[1].id, "log is now second (right slot)")
    }

    @Test
    fun `a third pane in one region still belongs to that region`() {
        val panels = listOf(
            spec("a", moveTo = ToolWindowMoveTo.LEFT_TOP),
            spec("b", moveTo = ToolWindowMoveTo.LEFT_BOTTOM),
            spec("c", moveTo = ToolWindowMoveTo.LEFT_TOP),
            spec("d", viewMode = ToolWindowViewMode.FLOAT, moveTo = ToolWindowMoveTo.LEFT_TOP),
        )

        val left = panels.filter { it.viewMode.isDocked }.regionFor(ToolWindowMoveTo.LEFT_TOP, ToolWindowMoveTo.LEFT_BOTTOM)

        assertEquals(
            listOf("a", "c", "b"),
            left.map { it.id },
            "every pane of the region is kept - the corner ones first - and the floating one is not part of it",
        )
    }

    @Test
    fun `view modes split along the reserved-share and fold-away axes`() {
        // Reserved-share axis: only a pinned pane and WINDOW hold a permanent piece of the frame.
        assertTrue(ToolWindowViewMode.DOCK_PINNED.isDocked, "DOCK_PINNED mode must be docked")
        assertTrue(ToolWindowViewMode.WINDOW.isDocked, "WINDOW mode must be docked")
        assertFalse(ToolWindowViewMode.DOCK_UNPINNED.isDocked, "an unpinned pane covers the content, it does not reserve it")
        assertFalse(ToolWindowViewMode.UNDOCK.isDocked, "UNDOCK mode must not be docked")
        assertFalse(ToolWindowViewMode.FLOAT.isDocked, "FLOAT mode must not be docked")
        ToolWindowViewMode.entries.forEach { mode ->
            assertEquals(mode.isDocked, !mode.overlaysContent, "$mode must sit on exactly one side of the split")
        }

        // Fold-away axis: the two transient modes are the ones that hide when attention leaves.
        assertTrue(ToolWindowViewMode.DOCK_UNPINNED.autoHidesWhenUnfocused, "an unpinned pane is precisely the one that hides")
        assertTrue(ToolWindowViewMode.UNDOCK.autoHidesWhenUnfocused, "an undocked pane folds away too")
        assertFalse(ToolWindowViewMode.DOCK_PINNED.autoHidesWhenUnfocused, "a pinned pane stays until told otherwise")
        assertFalse(ToolWindowViewMode.FLOAT.autoHidesWhenUnfocused, "a floated window stays until told otherwise")
        assertFalse(ToolWindowViewMode.WINDOW.autoHidesWhenUnfocused, "WINDOW stays until told otherwise")

        // Only the two free windows can be dragged around the layer.
        assertTrue(ToolWindowViewMode.FLOAT.isFloatingWindow)
        assertTrue(ToolWindowViewMode.UNDOCK.isFloatingWindow)
        assertFalse(ToolWindowViewMode.DOCK_UNPINNED.isFloatingWindow, "an unpinned pane keeps its edge")
        assertFalse(ToolWindowViewMode.WINDOW.isFloatingWindow)
    }

    @Test
    fun `window mode claims the larger share once and no other mode moves the split`() {
        // WINDOW promises the pane the larger half, so a list holding 68% yields down to the window share.
        assertEquals(
            WINDOW_MODE_CONTENT_FRACTION,
            contentFractionForViewMode(ToolWindowViewMode.WINDOW, 0.68f),
        )
        // A split already friendlier to the pane is left alone: entering WINDOW never *shrinks* the pane.
        assertEquals(0.20f, contentFractionForViewMode(ToolWindowViewMode.WINDOW, 0.20f))
        // Every other mode keeps whatever the user dragged, so switching away and back loses no size.
        ToolWindowViewMode.entries.filter { it != ToolWindowViewMode.WINDOW }.forEach { mode ->
            assertEquals(0.68f, contentFractionForViewMode(mode, 0.68f), "$mode must not move the divider")
        }
    }

    @Test
    fun `undocked panels trigger close callback on dismiss request`() {
        var closed = false
        val spec = ToolWindowSpec(
            id = "undocked_panel",
            title = "Undocked Panel",
            visible = true,
            viewMode = ToolWindowViewMode.UNDOCK,
            onClose = { closed = true },
            content = {}
        )

        assertEquals(ToolWindowViewMode.UNDOCK, spec.viewMode)
        assertFalse(closed)
        spec.onClose()
        assertTrue(closed)
    }

    @Test
    fun `floating bounds are clamped fully inside the layer`() {
        val layer = IntSize(1000, 800)
        val min = IntSize(240, 180)

        // A pane restored from settings written by a wider window is pulled back on screen...
        val restored = clampFloatingBounds(Offset(1500f, 900f), IntSize(400, 300), layer, min)
        assertEquals(Offset(600f, 500f), restored.offset, "the whole window must stay reachable")
        assertEquals(IntSize(400, 300), restored.sizePx)

        // ...and a resize may not shrink it past its minimum or grow it past the layer.
        assertEquals(IntSize(240, 180), clampFloatingBounds(Offset.Zero, IntSize(10, 10), layer, min).sizePx)
        assertEquals(IntSize(1000, 800), clampFloatingBounds(Offset.Zero, IntSize(4000, 4000), layer, min).sizePx)

        // An unmeasured layer must not collapse the window to nothing.
        val unknown = clampFloatingBounds(Offset(20f, 30f), IntSize(300, 200), IntSize.Zero, min)
        assertEquals(IntSize(300, 200), unknown.sizePx)
    }

    @Test
    fun `a never-positioned window anchors to the corner of the edge it is docked to`() {
        val layer = IntSize(1000, 800)
        val size = IntSize(400, 300)

        assertEquals(Offset(16f, 16f), anchoredOffset(ToolWindowMoveTo.LEFT_TOP, size, layer, 16f))
        assertEquals(Offset(584f, 16f), anchoredOffset(ToolWindowMoveTo.RIGHT_TOP, size, layer, 16f))
        assertEquals(Offset(584f, 484f), anchoredOffset(ToolWindowMoveTo.RIGHT_BOTTOM, size, layer, 16f))
        assertEquals(Offset(16f, 484f), anchoredOffset(ToolWindowMoveTo.LEFT_BOTTOM, size, layer, 16f))
    }

    @Test
    fun `detaching a window asks the layer for its edge corner and a move-to does the same`() {
        val model = ServerLogWindowModel(AppSettingsRepository(tempDir))

        // A docked window keeps its remembered place when the mode changes to another docked one.
        model.onLayoutEvent(ToolWindowLayoutAction.SetFloatingBounds(300f, 200f, 500f, 400f))
        model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.DOCK_PINNED))
        assertEquals(300f, model.state.value.floatingOffsetX, "a docked pane has nowhere to be anchored")

        // Detaching clears the place, which is the request the floating window reads as "give me a corner".
        model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.FLOAT))
        assertEquals(0f, model.state.value.floatingOffsetX)
        assertEquals(0f, model.state.value.floatingOffsetY)

        // A hop between the two free modes keeps the spot the window reported.
        model.onLayoutEvent(ToolWindowLayoutAction.SetFloatingBounds(640f, 20f, 500f, 400f))
        model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.UNDOCK))
        assertEquals(640f, model.state.value.floatingOffsetX, "an undock must not throw the window back to a corner")

        // Move To means something for a free window: it follows the edge the user just picked.
        model.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(ToolWindowMoveTo.LEFT_TOP))
        assertEquals(ToolWindowMoveTo.LEFT_TOP, model.state.value.moveTo)
        assertEquals(0f, model.state.value.floatingOffsetX, "a free window is re-anchored, not left where it was")

        // ...and does not for a docked one, whose place is the split's business rather than its own.
        model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.DOCK_PINNED))
        model.onLayoutEvent(ToolWindowLayoutAction.SetFloatingBounds(120f, 40f, 500f, 400f))
        model.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(ToolWindowMoveTo.BOTTOM_LEFT))
        assertEquals(120f, model.state.value.floatingOffsetX, "a docked pane keeps its stored window geometry")
    }

    @Test
    fun `window mode hands the pane the larger share through the model`() {
        val model = ServerLogWindowModel(AppSettingsRepository(tempDir))

        model.onLayoutEvent(ToolWindowLayoutAction.SetSplitFraction(0.68f))
        model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.WINDOW))
        assertEquals(WINDOW_MODE_CONTENT_FRACTION, model.state.value.splitFraction, "the pane claims the larger half")

        // Leaving the mode does not give the share back, so the pane the user grew stays grown.
        model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.DOCK_PINNED))
        assertEquals(WINDOW_MODE_CONTENT_FRACTION, model.state.value.splitFraction)
    }

    private fun spec(
        id: String,
        viewMode: ToolWindowViewMode = ToolWindowViewMode.DOCK_PINNED,
        moveTo: ToolWindowMoveTo = ToolWindowMoveTo.RIGHT_TOP,
    ): ToolWindowSpec = ToolWindowSpec(
        id = id,
        title = id,
        visible = true,
        viewMode = viewMode,
        moveTo = moveTo,
        content = {},
    )
}
