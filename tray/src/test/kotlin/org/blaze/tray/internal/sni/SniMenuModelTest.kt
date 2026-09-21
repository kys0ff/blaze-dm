package org.blaze.tray.internal.sni

import org.blaze.tray.api.TrayMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SniMenuModelTest {

    private var toggled = 0

    private val menu = listOf(
        TrayMenuItem.WindowToggle("Show Window", "Hide Window") { toggled++ },
        TrayMenuItem.Item("Pause All") {},
        TrayMenuItem.Item("Resume All") {},
        TrayMenuItem.Separator,
        TrayMenuItem.Item("Quit") {},
    )

    private fun model(windowVisible: Boolean = true): SniMenuModel =
        SniMenuModel().apply { rebuild(menu, windowVisible) }

    @Test
    fun `entries keep positional ids in menu order`() {
        assertEquals(listOf(1, 2, 3, 4, 5), model().entries.map { it.id })
        assertEquals(
            listOf(false, false, false, true, false),
            model().entries.map { it.isSeparator },
        )
    }

    @Test
    fun `toggle label follows window visibility`() {
        assertEquals("Hide Window", model(true).entryFor(1)?.label)
        assertEquals("Show Window", model(false).entryFor(1)?.label)
    }

    @Test
    fun `click callbacks travel with the entries`() {
        val m = model()
        assertNotNull(m.entryFor(1)?.onClick).invoke()
        assertEquals(1, toggled)
        assertNotNull(m.entryFor(5)?.onClick)
    }

    @Test
    fun `separators are not clickable`() {
        val m = model()
        assertNull(m.entryFor(4))
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
        val quit = model().entryFor(5)!!

        val props = model().layoutProperties(quit)

        assertEquals("standard", props.getValue("type").value)
        assertEquals("Quit", props.getValue("label").value)
        assertEquals(true, props.getValue("enabled").value)
        assertEquals(true, props.getValue("visible").value)
    }

    @Test
    fun `disabled item reports enabled false`() {
        val m = SniMenuModel().apply {
            rebuild(listOf(TrayMenuItem.Item("Nope", enabled = false) {}), true)
        }

        assertEquals(false, m.layoutProperties(m.entries.first()).getValue("enabled").value)
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
        val pause = model().entries.first { it.label == "Pause All" }

        val struct = model().itemStruct(pause, listOf("label"))

        assertEquals(setOf("label", "id"), struct.properties.keys)
        assertEquals("Pause All", struct.properties.getValue("label").value)
        assertEquals(2, struct.properties.getValue("id").value)
    }

    @Test
    fun `group and single property lookups`() {
        val m = model()

        assertEquals(2, m.groupProperties(2).getValue("id").value)
        assertEquals(setOf("id"), m.groupProperties(SniMenuModel.ROOT_ID).keys)
        assertEquals("Quit", m.singleProperty(5, "label")?.value)
        assertNull(m.singleProperty(5, "nope"))
        assertNull(m.singleProperty(999, "label"))
    }
}
