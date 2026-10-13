package org.blaze.presentation.application.components

import org.blaze.i18n.EnStrings
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.search.AppActions
import org.island.ui.menu.MenuEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleBarMenuTest {

    private val strings = EnStrings
    private val bus = AppCommandBus()

    @Test
    fun `title bar menu categories exist and have localized labels`() {
        val categories = TitleBarMenuCategory.entries
        assertEquals(5, categories.size)
        assertEquals("File", strings.menu.file)
        assertEquals("Edit", strings.menu.edit)
        assertEquals("View", strings.menu.view)
        assertEquals("Tools", strings.menu.tools)
        assertEquals("Help", strings.menu.help)
    }

    @Test
    fun `entries for category generates expected actions`() {
        val fileEntries = entriesForCategory(TitleBarMenuCategory.File, strings, bus, false) {}
        val fileActions = fileEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(fileActions.contains(strings.menu.newDownload))
        assertTrue(fileActions.contains(strings.menu.settings))
        assertTrue(fileActions.contains(strings.menu.exit))

        val editEntries = entriesForCategory(TitleBarMenuCategory.Edit, strings, bus, false) {}
        val editActions = editEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(editActions.contains(strings.downloads.selection.selectAll))

        val viewEntries = entriesForCategory(TitleBarMenuCategory.View, strings, bus, false) {}
        val viewActions = viewEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(viewActions.contains(strings.menu.toggleDetails))

        val toolsEntries = entriesForCategory(TitleBarMenuCategory.Tools, strings, bus, false) {}
        val toolsActions = toolsEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(toolsActions.contains(strings.menu.pauseAll))
        assertTrue(toolsActions.contains(strings.menu.resumeAll))
        assertTrue(toolsActions.contains(strings.menu.searchEverywhere))
        // The Server Logs entry and the Local Server submenu live in the right category (Tools).
        assertTrue(toolsActions.contains(strings.menu.serverLogs))
        assertTrue(toolsEntries.filterIsInstance<MenuEntry.Submenu>().any { it.label == strings.menu.server })

        val helpEntries = entriesForCategory(TitleBarMenuCategory.Help, strings, bus, false) {}
        val helpActions = helpEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(helpActions.contains(strings.menu.about))
    }

    @Test
    fun `server submenu lifecycle rows reflect the running state`() {
        fun serverSubmenu(running: Boolean): List<MenuEntry> {
            val tools = entriesForCategory(TitleBarMenuCategory.Tools, strings, bus, running) {}
            return tools.filterIsInstance<MenuEntry.Submenu>()
                .first { it.label == strings.menu.server }
                .children
                .filterIsInstance<MenuEntry.Action>()
        }

        val stopped = serverSubmenu(running = false).associate { it.label to it.enabled }
        assertTrue(stopped.getValue(strings.menu.startServer), "Start is enabled while stopped")
        assertTrue(!stopped.getValue(strings.menu.stopServer), "Stop is disabled while stopped")
        assertTrue(!stopped.getValue(strings.menu.restartServer), "Restart is disabled while stopped")
        assertTrue(!stopped.getValue(strings.menu.copyEndpoint), "Copy Endpoint is disabled while stopped")

        val running = serverSubmenu(running = true).associate { it.label to it.enabled }
        assertTrue(!running.getValue(strings.menu.startServer), "Start is disabled while running")
        assertTrue(running.getValue(strings.menu.stopServer), "Stop is enabled while running")
        assertTrue(running.getValue(strings.menu.restartServer), "Restart is enabled while running")
        assertTrue(running.getValue(strings.menu.copyEndpoint), "Copy Endpoint is enabled while running")
    }

    @Test
    fun `all app actions are accessible across the title bar menu categories`() {
        val allMenuActions = TitleBarMenuCategory.entries.flatMap { category ->
            entriesForCategory(category, strings, bus, false) {}
                .filterIsInstance<MenuEntry.Action>()
                .map { it.label }
        }.toSet()

        for (action in AppActions.all) {
            val label = action.label(strings)
            assertTrue(
                allMenuActions.contains(label),
                "AppAction '${action.command}' with label '$label' should be present in title bar menus",
            )
        }
    }
}
