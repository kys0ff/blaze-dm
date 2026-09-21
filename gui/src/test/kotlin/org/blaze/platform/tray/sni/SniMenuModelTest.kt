package org.blaze.platform.tray.sni

import org.blaze.platform.tray.TrayLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SniMenuModelTest {

    private val labels = TrayLabels(
        show = "Show Window",
        hide = "Hide Window",
        pauseAll = "Pause All",
        resumeAll = "Resume All",
        quit = "Quit",
    )

    private fun model(windowVisible: Boolean = true): SniMenuModel =
        SniMenuModel().apply { rebuild(labels, windowVisible) }

    @Test
    fun `menu order mirrors the awt tray`() {
        assertEquals(
            listOf(
                TrayMenuAction.TOGGLE,
                TrayMenuAction.PAUSE_ALL,
                TrayMenuAction.RESUME_ALL,
                TrayMenuAction.SEPARATOR,
                TrayMenuAction.QUIT,
            ),
            model().entries.map { it.action },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), model().entries.map { it.id })
    }

    @Test
    fun `toggle label follows window visibility`() {
        assertEquals("Hide Window", model(true).entryFor(SniMenuModel.ID_TOGGLE)?.label)
        assertEquals("Show Window", model(false).entryFor(SniMenuModel.ID_TOGGLE)?.label)
    }

    @Test
    fun `separators are not clickable`() {
        val m = model()
        assertNull(m.entryFor(SniMenuModel.ID_SEPARATOR))
        assertNull(m.entryFor(999))
    }

    @Test
    fun `separator layout only exposes its type`() {
        val separator = model().entries.first { it.isSeparator }

        val props = model().layoutProperties(separator)

        assertEquals(setOf("type"), props.keys)
        assertEquals("separator", props.getValue("type").value)
    }

    @Test
    fun `standard item layout carries label enabled and visible flags`() {
        val quit = model().entries.first { it.action == TrayMenuAction.QUIT }

        val props = model().layoutProperties(quit)

        assertEquals("standard", props.getValue("type").value)
        assertEquals("Quit", props.getValue("label").value)
        assertEquals(true, props.getValue("enabled").value)
        assertEquals(true, props.getValue("visible").value)
    }

    @Test
    fun `root struct lists every entry as a nested layout struct variant`() {
        val root = model().rootStruct(emptyList(), includeChildren = true)

        assertEquals(SniMenuModel.ROOT_ID, root.id)
        assertEquals(5, root.children.size)
        assertTrue(root.children.all { it.sig == "(ia{sv}av)" })

        @Suppress("UNCHECKED_CAST")
        val structs = root.children.map { it.value as MenuLayoutStruct }
        assertEquals(listOf(1, 2, 3, 4, 5), structs.map { it.id })
        assertEquals("Pause All", structs[1].properties.getValue("label").value)
        assertTrue(structs.all { it.children.isEmpty() }, "tray menu is flat")
    }

    @Test
    fun `root struct omits children when recursion depth is zero`() {
        val root = model().rootStruct(emptyList(), includeChildren = false)

        assertTrue(root.children.isEmpty())
    }

    @Test
    fun `item struct filters properties by request and always carries id`() {
        val pause = model().entries.first { it.action == TrayMenuAction.PAUSE_ALL }

        val struct = model().itemStruct(pause, listOf("label"))

        assertEquals(setOf("label", "id"), struct.properties.keys)
        assertEquals("Pause All", struct.properties.getValue("label").value)
        assertEquals(2, struct.properties.getValue("id").value)
    }

    @Test
    fun `group and single property lookups`() {
        val m = model()

        assertEquals(2, m.groupProperties(SniMenuModel.ID_PAUSE_ALL).getValue("id").value)
        assertEquals(setOf("id"), m.groupProperties(SniMenuModel.ROOT_ID).keys)
        assertEquals("Quit", m.singleProperty(SniMenuModel.ID_QUIT, "label")?.value)
        assertNull(m.singleProperty(SniMenuModel.ID_QUIT, "nope"))
        assertNull(m.singleProperty(999, "label"))
    }
}
