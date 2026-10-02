package org.blaze.presentation.application.components

import org.blaze.i18n.EnStrings
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.MenuEntry
import org.blaze.presentation.search.AppActions
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
        val fileEntries = entriesForCategory(TitleBarMenuCategory.File, strings, bus) {}
        val fileActions = fileEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(fileActions.contains(strings.menu.newDownload))
        assertTrue(fileActions.contains(strings.menu.settings))
        assertTrue(fileActions.contains(strings.menu.exit))

        val editEntries = entriesForCategory(TitleBarMenuCategory.Edit, strings, bus) {}
        val editActions = editEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(editActions.contains(strings.downloads.selection.selectAll))

        val viewEntries = entriesForCategory(TitleBarMenuCategory.View, strings, bus) {}
        val viewActions = viewEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(viewActions.contains(strings.menu.toggleDetails))

        val toolsEntries = entriesForCategory(TitleBarMenuCategory.Tools, strings, bus) {}
        val toolsActions = toolsEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(toolsActions.contains(strings.menu.pauseAll))
        assertTrue(toolsActions.contains(strings.menu.resumeAll))
        assertTrue(toolsActions.contains(strings.menu.searchEverywhere))

        val helpEntries = entriesForCategory(TitleBarMenuCategory.Help, strings, bus) {}
        val helpActions = helpEntries.filterIsInstance<MenuEntry.Action>().map { it.label }
        assertTrue(helpActions.contains(strings.menu.about))
    }

    @Test
    fun `all app actions are accessible across the title bar menu categories`() {
        val allMenuActions = TitleBarMenuCategory.entries.flatMap { category ->
            entriesForCategory(category, strings, bus) {}
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
