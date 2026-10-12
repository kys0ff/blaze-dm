package org.blaze.presentation.screens.downloads

import kotlinx.coroutines.runBlocking
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.components.panel.DetailsLayoutStore
import org.blaze.presentation.components.panel.ToolWindowLayoutAction
import org.blaze.presentation.components.panel.ToolWindowMoveTo
import org.blaze.presentation.components.panel.ToolWindowResize
import org.blaze.presentation.components.panel.ToolWindowViewMode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Details pane as a first-class dockable model: its layout is folded from the shared
 * [org.blaze.presentation.components.panel.ToolWindowScreenModel] into a synchronous [DetailsPanelScreenModel.state],
 * persisted through [DetailsLayoutStore], and it keeps the two Details-only behaviors that used to live in
 * the downloads model — the pane-specific Resize presets (which also re-dock the pane to the stretched edge)
 * and the separately-persisted group-tabs flag.
 */
class DetailsPanelScreenModelTest {

    private val tempDir = Files.createTempDirectory("blaze-details-panel-model-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun model(repository: AppSettingsRepository = AppSettingsRepository(tempDir)) =
        DetailsPanelScreenModel(DetailsLayoutStore(repository)) to repository

    @Test
    fun `state seeds from the persisted defaults`() {
        val (m, _) = model()
        val s = m.state.value
        assertFalse(s.visible)
        assertEquals(ToolWindowViewMode.DOCK_PINNED, s.viewMode)
        assertEquals(ToolWindowMoveTo.RIGHT_TOP, s.moveTo)
        assertEquals(0.68f, s.splitFraction)
        assertFalse(s.groupTabs)
    }

    @Test
    fun `layout events reduce synchronously and persist to app settings`() = runBlocking {
        val (m, repository) = model()

        m.setVisible(true)
        m.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(ToolWindowMoveTo.LEFT_TOP))
        m.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(ToolWindowViewMode.FLOAT))

        val live = m.state.value
        assertTrue(live.visible)
        assertEquals(ToolWindowMoveTo.LEFT_TOP, live.moveTo)
        assertEquals(ToolWindowViewMode.FLOAT, live.viewMode)

        Thread.sleep(500)
        val saved = repository.settings.value
        assertTrue(saved.detailsPaneVisible)
        assertEquals("LEFT_TOP", saved.detailsMoveTo)
        assertEquals("FLOAT", saved.detailsViewMode)
    }

    @Test
    fun `group tabs persists on its own without touching the layout write`() = runBlocking {
        val (m, repository) = model()

        m.setGroupTabs(true)
        assertTrue(m.state.value.groupTabs)

        Thread.sleep(500)
        assertTrue(repository.settings.value.groupDetailsTabs)
    }

    @Test
    fun `resize presets set the list share and re-dock to the stretched edge`() {
        val (m, _) = model()

        m.resize(ToolWindowResize.STRETCH_LEFT)
        assertEquals(0.30f, m.state.value.splitFraction)
        assertEquals(ToolWindowMoveTo.LEFT_TOP, m.state.value.moveTo)

        m.resize(ToolWindowResize.STRETCH_BOTTOM)
        assertEquals(ToolWindowMoveTo.BOTTOM_LEFT, m.state.value.moveTo)

        // MAXIMIZE floors the list's share but keeps the current edge (no re-dock).
        m.resize(ToolWindowResize.MAXIMIZE)
        assertEquals(0.15f, m.state.value.splitFraction)
        assertEquals(ToolWindowMoveTo.BOTTOM_LEFT, m.state.value.moveTo)
    }

    @Test
    fun `rehydrates a persisted layout on construction`() = runBlocking {
        val repository = AppSettingsRepository(tempDir)
        repository.updateSettings {
            it.copy(
                detailsPaneVisible = true,
                detailsViewMode = "WINDOW",
                detailsMoveTo = "BOTTOM_RIGHT",
                detailsSplitFraction = 0.4f,
                groupDetailsTabs = true,
            )
        }

        val (m, _) = model(repository)
        val s = m.state.value
        assertTrue(s.visible)
        assertEquals(ToolWindowViewMode.WINDOW, s.viewMode)
        assertEquals(ToolWindowMoveTo.BOTTOM_RIGHT, s.moveTo)
        assertEquals(0.4f, s.splitFraction)
        assertTrue(s.groupTabs)
    }
}
