package org.blaze.presentation.components.panel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsViewMode
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
        var movedTo: DetailsMoveTo? = null
        var innerFraction: Float? = null

        val spec = ToolWindowSpec(
            id = "test_panel",
            title = "Test Panel",
            visible = true,
            viewMode = DetailsViewMode.DOCK_PINNED,
            moveTo = DetailsMoveTo.RIGHT_TOP,
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
        assertEquals(DetailsViewMode.DOCK_PINNED, spec.viewMode)
        assertEquals(DetailsMoveTo.RIGHT_TOP, spec.moveTo)

        spec.onMoveTo(DetailsMoveTo.LEFT_BOTTOM)
        assertEquals(DetailsMoveTo.LEFT_BOTTOM, movedTo)

        spec.onInnerSplitFractionChange(0.40f)
        assertEquals(0.40f, innerFraction)
    }

    @Test
    fun `server log model updates and persists floating bounds and inner split fraction`() = runBlocking {
        val repository = AppSettingsRepository(tempDir)
        val model = ServerLogWindowModel(repository)

        model.setInnerSplitFraction(0.35f)
        model.setFloatingBounds(120f, 200f, 500f, 400f)

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
        val p1 = ToolWindowSpec(
            id = "p1", title = "P1", visible = true,
            viewMode = DetailsViewMode.DOCK_PINNED, moveTo = DetailsMoveTo.RIGHT_TOP, content = {}
        )
        val p2 = ToolWindowSpec(
            id = "p2", title = "P2", visible = true,
            viewMode = DetailsViewMode.DOCK_PINNED, moveTo = DetailsMoveTo.RIGHT_BOTTOM, content = {}
        )
        val p3 = ToolWindowSpec(
            id = "p3", title = "P3", visible = true,
            viewMode = DetailsViewMode.FLOAT, moveTo = DetailsMoveTo.BOTTOM_RIGHT, content = {}
        )

        val panels = listOf(p1, p2, p3)
        val docked = panels.filter { it.visible && it.viewMode.isDocked }
        val floating = panels.filter { it.visible && !it.viewMode.isDocked }

        assertEquals(2, docked.size)
        assertEquals(1, floating.size)
        assertEquals("p3", floating.first().id)

        val rightDocked = docked.filter { it.moveTo == DetailsMoveTo.RIGHT_TOP || it.moveTo == DetailsMoveTo.RIGHT_BOTTOM }
        assertEquals(2, rightDocked.size)
        assertTrue(rightDocked.contains(p1))
        assertTrue(rightDocked.contains(p2))
    }

    @Test
    fun `changing panel position to bottom left and bottom right sorts and reverses slot order`() {
        val p1 = ToolWindowSpec(
            id = "details", title = "Details", visible = true,
            viewMode = DetailsViewMode.DOCK_PINNED, moveTo = DetailsMoveTo.BOTTOM_RIGHT, content = {}
        )
        val p2 = ToolWindowSpec(
            id = "log", title = "Log", visible = true,
            viewMode = DetailsViewMode.DOCK_PINNED, moveTo = DetailsMoveTo.BOTTOM_LEFT, content = {}
        )

        val initialPanels = listOf(p1, p2)
        val bottomPanels = initialPanels.filter { it.moveTo == DetailsMoveTo.BOTTOM_LEFT || it.moveTo == DetailsMoveTo.BOTTOM_RIGHT }
            .sortedWith(compareBy { if (it.moveTo == DetailsMoveTo.BOTTOM_LEFT) 0 else 1 })

        assertEquals("log", bottomPanels[0].id, "BOTTOM_LEFT panel is first (left slot)")
        assertEquals("details", bottomPanels[1].id, "BOTTOM_RIGHT panel is second (right slot)")

        // User swaps their positions: details -> BOTTOM_LEFT, log -> BOTTOM_RIGHT
        val p1Swapped = p1.copy(moveTo = DetailsMoveTo.BOTTOM_LEFT)
        val p2Swapped = p2.copy(moveTo = DetailsMoveTo.BOTTOM_RIGHT)

        val reversedPanels = listOf(p1Swapped, p2Swapped).filter { it.moveTo == DetailsMoveTo.BOTTOM_LEFT || it.moveTo == DetailsMoveTo.BOTTOM_RIGHT }
            .sortedWith(compareBy { if (it.moveTo == DetailsMoveTo.BOTTOM_LEFT) 0 else 1 })

        assertEquals("details", reversedPanels[0].id, "details is now first (left slot)")
        assertEquals("log", reversedPanels[1].id, "log is now second (right slot)")
    }

    @Test
    fun `undocked and float view modes classify as floating windows`() {
        assertFalse(DetailsViewMode.FLOAT.isDocked, "FLOAT mode must not be docked")
        assertFalse(DetailsViewMode.UNDOCK.isDocked, "UNDOCK mode must not be docked")
        assertTrue(DetailsViewMode.DOCK_PINNED.isDocked, "DOCK_PINNED mode must be docked")
        assertTrue(DetailsViewMode.DOCK_UNPINNED.isDocked, "DOCK_UNPINNED mode must be docked")
        assertTrue(DetailsViewMode.WINDOW.isDocked, "WINDOW mode must be docked")
    }
}
