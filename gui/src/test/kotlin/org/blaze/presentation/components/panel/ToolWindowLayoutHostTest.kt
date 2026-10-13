package org.blaze.presentation.components.panel

import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import org.island.ui.toolwindow.ToolWindowLayoutAction
import org.island.ui.toolwindow.ToolWindowMoveTo
import org.island.ui.toolwindow.ToolWindowViewMode
import org.island.ui.toolwindow.WINDOW_MODE_CONTENT_FRACTION
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Blaze-side wiring of the island layout rules: the pure decisions live in `:island-ui`
 * (see its `ToolWindowLayoutTest`/`ToolWindowLayoutHostTest`); what stays here is proof that the
 * app's window models fold actions through [org.island.ui.toolwindow.reduceToolWindowLayout] and
 * persist the result through their [org.island.ui.toolwindow.ToolWindowLayoutStore].
 */
class ToolWindowLayoutHostTest {

    private val tempDir = Files.createTempDirectory("blaze-tool-window-host-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
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
}
