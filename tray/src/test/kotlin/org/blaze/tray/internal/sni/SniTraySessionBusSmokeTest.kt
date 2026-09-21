package org.blaze.tray.internal.sni

import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayMenuItem
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TEMPORARY smoke test: runs the real SniTrayService against the desktop session
 * bus, then remotely introspects/calls the exported item like Plasma would.
 */
class SniTraySessionBusSmokeTest {

    @Test
    fun `install exports a working SNI item and dbusmenu on the session bus`() {
        val service = SniTrayService()
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS") == null || !service.isSupported) {
            println("SKIP: no session bus with a StatusNotifierWatcher (needs a Linux desktop)")
            service.dispose()
            return
        }

        val clicked = CountDownLatch(1)
        val config = TrayConfig(
            id = "org.blaze.SmokeTest",
            title = "Smoke",
            icon = { size -> java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB) },
            menu = listOf(
                TrayMenuItem.WindowToggle("Show", "Hide") { clicked.countDown() },
                TrayMenuItem.Item("Pause All") {},
                TrayMenuItem.Item("Resume All") {},
                TrayMenuItem.Separator,
                TrayMenuItem.Item("Quit") {},
            ),
        )

        val control = DBusConnectionBuilder.forSessionBus().build()
        try {
            val before = registeredItems(control)
            service.install(config)

            // The watcher may finish validating asynchronously; poll briefly.
            var mine: String? = null
            val deadline = System.currentTimeMillis() + 3_000
            while (mine == null && System.currentTimeMillis() < deadline) {
                mine = (registeredItems(control) - before).firstOrNull()
                if (mine == null) Thread.sleep(100)
            }
            assertTrue(mine != null, "no new item registered; entries=${registeredItems(control)}")
            val slash = mine.indexOf('/')
            val busName = if (slash >= 0) mine.substring(0, slash) else mine
            val itemPath = if (slash >= 0) mine.substring(slash) else "/StatusNotifierItem"
            println("Registered as $busName at $itemPath")

            // --- SNI properties, exactly the calls Plasma makes
            val sniProps = control.getRemoteObject(busName, itemPath, Properties::class.java, false)
            val category = sniProps.Get<Any>("org.kde.StatusNotifierItem", "Category")
            assertEquals("ApplicationStatus", category)
            val title = sniProps.Get<Any>("org.kde.StatusNotifierItem", "Title")
            assertEquals("Smoke", title)
            val isMenu = sniProps.Get<Any>("org.kde.StatusNotifierItem", "ItemIsMenu")
            assertEquals(false, isMenu)
            val pixmap = sniProps.Get<Any>("org.kde.StatusNotifierItem", "IconPixmap")
            println("IconPixmap type=${pixmap!!::class.java.name} elem=" +
                (pixmap as? List<*>)?.firstOrNull()?.javaClass)
            val all = sniProps.GetAll("org.kde.StatusNotifierItem")
            assertTrue(all.containsKey("Menu"), "GetAll must expose Menu: ${all.keys}")
            val menuPath = (all.getValue("Menu").value as org.freedesktop.dbus.DBusPath).path

            // --- the host first introspects the menu object; the XML must carry the
            // exact spec signatures or Plasma refuses to use the interface
            val xml = control.getRemoteObject(busName, menuPath, Introspectable::class.java, false).Introspect()
            assertTrue("(ia{sv}av)" in xml, "layout struct missing from introspection:\n$xml")
            assertTrue("GetLayout" in xml && "com.canonical.dbusmenu" in xml)

            // --- dbusmenu: AboutShow then GetLayout
            val menu = control.getRemoteObject(busName, menuPath, DBusMenu::class.java, false)
            assertTrue(menu.AboutShow())

            val layout = menu.GetLayout(0, -1, listOf("label", "type", "enabled", "visible"))
            println("GetLayout revision=${layout.revision}")
            assertEquals(UInt32(1), layout.revision)
            val childProps = layout.layout.children.map { structOf(it).second }
            assertEquals(5, childProps.size)
            val labelsRemote = childProps.map { it["label"]?.value }
            assertEquals(listOf("Hide", "Pause All", "Resume All", null, "Quit"), labelsRemote)

            // --- group properties round trip (a(ia{sv}))
            val grouped = menu.GetGroupProperties(listOf(0, 2), emptyList())
            assertEquals(2, grouped.size)
            assertEquals(2, grouped[1].id)

            // --- clicking the toggle item must reach the callback on the UI thread
            val toggleId = structOf(layout.layout.children[0]).first
            menu.Event(toggleId, "clicked", Variant(0, "i"), UInt32(0))
            assertTrue(clicked.await(5, TimeUnit.SECONDS), "Event callback never fired")
        } finally {
            service.dispose()
            runCatching { control.disconnect() }
        }
    }

    /** A child layout struct as unmarshalled by dbus-java (typed struct or raw array/list). */
    @Suppress("UNCHECKED_CAST")
    private fun structOf(child: Variant<*>): Pair<Int, Map<String, Variant<*>>> =
        when (val value = child.value) {
            is MenuLayoutStruct -> value.id to value.properties
            is Array<*> -> (value[0] as Int) to (value[1] as Map<String, Variant<*>>)
            is List<*> -> (value[0] as Int) to (value[1] as Map<String, Variant<*>>)
            else -> error("unexpected child representation: ${value?.javaClass}")
        }

    private fun registeredItems(control: org.freedesktop.dbus.connections.impl.DBusConnection): Set<String> {
        val props = control.getRemoteObject(
            "org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", Properties::class.java, false,
        )
        return when (val v = props.Get<Any>("org.kde.StatusNotifierWatcher", "RegisteredStatusNotifierItems")) {
            is Array<*> -> v.mapNotNull { it as? String }.toSet()
            is List<*> -> v.mapNotNull { it as? String }.toSet()
            else -> emptySet()
        }
    }
}
