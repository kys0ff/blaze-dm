package org.blaze.presentation.screens.serverlog

import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.island.ui.toolwindow.ToolWindowLayoutAction
import org.island.ui.toolwindow.ToolWindowMoveTo
import org.island.ui.toolwindow.ToolWindowResize
import org.island.ui.toolwindow.ToolWindowViewMode
import java.nio.file.Files
import kotlin.test.*

/**
 * Covers the Server Logs window's reduce-only presentation state and its write-back into the persisted
 * app settings. Visibility, docked layout and resize are folded straight into [ServerLogWindowModel.state];
 * the layout fields also land in `app.json` (debounced) so the window rehydrates across launches. The find
 * navigation is pure in-memory wrapping over a match count the panel reports.
 */
class ServerLogWindowModelTest {

    private val tempDir = Files.createTempDirectory("blaze-server-log-model-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun model(repository: AppSettingsRepository = AppSettingsRepository(tempDir)) =
        ServerLogWindowModel(repository) to repository

    @Test
    fun `state seeds from the persisted defaults`() {
        val (m, _) = model()

        val s = m.state.value
        assertFalse(s.visible)
        assertEquals(0.65f, s.splitFraction)
        assertEquals(ToolWindowViewMode.DOCK_PINNED, s.viewMode)
        assertEquals(ToolWindowMoveTo.BOTTOM_RIGHT, s.moveTo)
    }

    @Test
    fun `visibility and layout reduce and persist to app settings`() = runBlocking {
        val (m, repository) = model()

        m.setVisible(true)
        m.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(ToolWindowMoveTo.RIGHT_TOP))
        m.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.FLOAT))

        val live = m.state.value
        assertTrue(live.visible)
        assertEquals(ToolWindowMoveTo.RIGHT_TOP, live.moveTo)
        assertEquals(ToolWindowViewMode.FLOAT, live.viewMode)

        // The persist job debounces into screenModelScope; give it room to settle, then read the disk model.
        Thread.sleep(500)
        val saved = repository.settings.value
        assertTrue(saved.serverLogVisible)
        assertEquals("RIGHT_TOP", saved.serverLogMoveTo)
        assertEquals("FLOAT", saved.serverLogViewMode)
    }

    @Test
    fun `rehydrates a persisted layout on construction`() = runBlocking {
        val repository = AppSettingsRepository(tempDir)
        repository.updateSettings {
            it.copy(
                serverLogVisible = true,
                serverLogViewMode = "WINDOW",
                serverLogMoveTo = "BOTTOM_LEFT",
                serverLogSplitFraction = 0.4f,
            )
        }

        val (m, _) = model(repository)
        val s = m.state.value
        assertTrue(s.visible)
        assertEquals(ToolWindowViewMode.WINDOW, s.viewMode)
        assertEquals(ToolWindowMoveTo.BOTTOM_LEFT, s.moveTo)
        assertEquals(0.4f, s.splitFraction)
    }

    @Test
    fun `resize clamps the content fraction and maximize fills the window`() {
        val (m, _) = model()

        m.resize(ToolWindowResize.MAXIMIZE)
        assertEquals(0.15f, m.state.value.splitFraction)

        m.resize(ToolWindowResize.STRETCH_BOTTOM)
        m.resize(ToolWindowResize.STRETCH_BOTTOM)
        m.resize(ToolWindowResize.STRETCH_BOTTOM)
        // Never shrinks the content below its floor, whatever the preset stack.
        assertTrue(m.state.value.splitFraction >= 0.15f)
    }

    @Test
    fun `find navigation wraps and a new query resets the focus`() {
        val (m, _) = model()

        m.openFind()
        assertTrue(m.state.value.findBarOpen)

        m.setFindQuery("err")
        m.setMatches(3)
        assertEquals(0, m.state.value.currentMatch)

        m.nextMatch()
        m.nextMatch()
        m.nextMatch()
        assertEquals(0, m.state.value.currentMatch, "next wraps past the last match")

        m.previousMatch()
        assertEquals(2, m.state.value.currentMatch, "previous wraps before the first match")

        // A shorter match set clamps the focused index back into range.
        m.setMatches(1)
        assertEquals(0, m.state.value.currentMatch)

        m.closeFind()
        val closed = m.state.value
        assertFalse(closed.findBarOpen)
        assertEquals("", closed.findQuery)
        assertEquals(0, closed.matchCount)
    }
}
