package org.blaze.tray.internal.sni

import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayMenuItem
import org.blaze.tray.api.TrayService
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.awt.EventQueue
import java.awt.image.BufferedImage

/**
 * [TrayService] backed by the StatusNotifierItem protocol (`org.kde.StatusNotifierItem`
 * + `com.canonical.dbusmenu`) over the session D-Bus — the tray mechanism modern Linux
 * desktops (KDE Plasma in particular) actually implement. AWT's legacy XEmbed tray is
 * displayed by Plasma but never delivers clicks, so on Linux this backend replaces
 * [org.blaze.tray.internal.awt.AwtTrayService].
 *
 * Every entry point degrades quietly: without a session bus or a watcher nothing is
 * registered and the app keeps running without a tray.
 */
internal class SniTrayService : TrayService {

    private val logger = LoggerFactory.getLogger(SniTrayService::class.java)

    private val lock = Any()
    private val menuModel = SniMenuModel()

    private var connection: DBusConnection? = null
    private var supportedCache: Boolean? = null
    private var config: TrayConfig? = null
    private var iconImage: BufferedImage? = null
    private var windowVisible = true

    override val isSupported: Boolean
        get() = synchronized(lock) {
            supportedCache ?: runCatching {
                openConnection().getRemoteObject(
                    DBUS_BUS, DBUS_PATH, DBus::class.java, false,
                ).NameHasOwner(WATCHER_BUS)
            }.getOrDefault(false).also {
                supportedCache = it
                if (!it) closeConnection()
            }
        }

    override fun install(config: TrayConfig) {
        dispose()
        if (!isSupported) {
            logger.info("No StatusNotifierWatcher on the session bus; skipping SNI tray install")
            return
        }
        runCatching {
            synchronized(lock) {
                val conn = openConnection()
                this.config = config
                iconImage = config.icon.create(ICON_SIZE)
                menuModel.rebuild(config.menu, windowVisible)

                conn.exportObject(MENU_PATH, MenuImpl())
                conn.exportObject(ITEM_PATH, ItemImpl())

                // KDE's watcher expects the bare bus name and assumes the item lives at
                // /StatusNotifierItem; passing "name:/path" makes it treat the whole
                // string as a bus name and silently drop the registration.
                conn.getRemoteObject(
                    WATCHER_BUS, WATCHER_PATH, StatusNotifierWatcher::class.java, false,
                ).RegisterStatusNotifierItem(conn.uniqueName)
            }
        }.onFailure {
            logger.warn("Failed to register the SNI tray item; continuing without a tray", it)
            closeConnection()
        }
    }

    override fun setWindowVisible(visible: Boolean) {
        windowVisible = visible
        config?.let { menuModel.rebuild(it.menu, visible) }
    }

    override fun dispose() {
        synchronized(lock) {
            config = null
            iconImage = null
            closeConnection()
        }
    }

    private fun openConnection(): DBusConnection = synchronized(lock) {
        connection ?: DBusConnectionBuilder.forSessionBus().build().also { connection = it }
    }

    private fun closeConnection() {
        val conn = connection ?: return
        runCatching { conn.disconnect() }
            .onFailure { logger.warn("Failed to close the D-Bus session connection", it) }
        connection = null
        supportedCache = null
    }

    /** Tray callbacks arrive on dbus-java worker threads; hop onto the UI thread. */
    private fun onUi(block: () -> Unit) {
        EventQueue.invokeLater {
            runCatching(block).onFailure { logger.warn("Tray action failed", it) }
        }
    }

    private fun pixmapValue(): List<Array<Any>> =
        iconImage?.let { SniIconPixmap.encode(it) } ?: emptyList()

    // ---------------------------------------------------------------- SNI item

    private inner class ItemImpl : StatusNotifierItem, Properties {

        override fun getObjectPath(): String = ITEM_PATH

        override fun Activate(x: UInt32, y: UInt32) {
            onUi { toggleWindow() }
        }

        override fun SecondaryActivate(x: UInt32, y: UInt32) {
            onUi { toggleWindow() }
        }

        /** Activate/SecondaryActivate: run the caller's window-toggle menu callback. */
        private fun toggleWindow() {
            config?.menu?.filterIsInstance<TrayMenuItem.WindowToggle>()
                ?.firstOrNull()?.onClick?.invoke()
        }

        // Plasma pops the dbusmenu itself; no dedicated context menu to show.
        override fun ContextMenu(x: UInt32, y: UInt32) = Unit

        override fun Scroll(delta: Int, orientation: String) = Unit

        override fun <A : Any> Get(iface: String, propName: String): A =
            sniProperties(iface)[propName] as A?
                ?: throw UnknownProperty("Unknown SNI property: $propName")

        override fun <A : Any> Set(iface: String, propName: String, value: A) {
            throw DBusExecutionException("StatusNotifierItem properties are read-only")
        }

        override fun GetAll(iface: String): Map<String, Variant<*>> = sniProperties(iface)

        private fun sniProperties(iface: String): Map<String, Variant<*>> {
            if (iface.isNotEmpty() && iface != SNI_IFACE) return emptyMap()
            val cfg = config
            return buildMap {
                put("Id", Variant(cfg?.id ?: DEFAULT_ID, "s"))
                put("Category", Variant("ApplicationStatus", "s"))
                put("Title", Variant(cfg?.title ?: "", "s"))
                put("Status", Variant("Active", "s"))
                put("IconName", Variant("", "s"))
                put("IconPixmap", Variant(pixmapValue(), "a(iiay)"))
                put("OverlayIconName", Variant("", "s"))
                put("OverlayIconPixmap", Variant(emptyList<Array<Any>>(), "a(iiay)"))
                put("AttentionIconName", Variant("", "s"))
                put("AttentionIconPixmap", Variant(emptyList<Array<Any>>(), "a(iiay)"))
                put("AttentionMovieName", Variant("", "s"))
                put(
                    "ToolTip",
                    Variant(arrayOf<Any>("", cfg?.title ?: "", emptyMap<String, Variant<*>>()), "(ssa{sv})"),
                )
                // Left click must reach us (Activate) instead of opening the menu directly.
                put("ItemIsMenu", Variant(false, "b"))
                put("Menu", Variant(DBusPath(MENU_PATH), "o"))
            }
        }
    }

    // ---------------------------------------------------------------- dbusmenu

    private inner class MenuImpl : DBusMenu, Properties {

        override fun getObjectPath(): String = MENU_PATH

        override fun GetLayout(
            parentId: Int,
            recursionDepth: Int,
            propertyNames: List<String>,
        ): GetLayoutTuple<UInt32, MenuLayoutStruct> {
            // Spec out-args: `u revision` + `(ia{sv}av) layout` — one recursive struct;
            // each child variant carries the same struct shape.
            val layout = if (parentId == SniMenuModel.ROOT_ID) {
                menuModel.rootStruct(propertyNames, includeChildren = recursionDepth != 0)
            } else {
                val entry = menuModel.entries.firstOrNull { it.id == parentId }
                    ?: throw DBusExecutionException("Unknown menu item id: $parentId")
                // The tray menu is flat; items never have children.
                menuModel.itemStruct(entry, propertyNames)
            }
            return GetLayoutTuple(UInt32(1), layout)
        }

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            val onClick = menuModel.entryFor(id)?.onClick ?: return
            onUi { onClick() }
        }

        // Always claim the layout changed: hosts (like Plasma) then re-run GetLayout on
        // every open, which keeps Show/Hide labels fresh without LayoutUpdated signals.
        override fun AboutShow(): Boolean = true

        override fun AboutToShow(id: Int): Boolean = true

        override fun GetProperty(id: Int, name: String): Variant<*> =
            menuModel.singleProperty(id, name)
                ?: throw DBusExecutionException("No property '$name' for menu item $id")

        override fun GetGroupProperties(
            ids: List<Int>,
            propertyNames: List<String>,
        ): List<MenuPropertiesStruct> =
            ids.map { id -> MenuPropertiesStruct(id, menuModel.groupProperties(id)) }

        override fun SetProperty(property: String, value: Variant<*>) = Unit

        override fun SetGroupProperty(id: Int, property: String, value: Variant<*>) = Unit

        override fun SendEvent(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) = Unit

        override fun <A : Any> Get(iface: String, propName: String): A =
            menuProperties(iface)[propName] as A?
                ?: throw UnknownProperty("Unknown dbusmenu property: $propName")

        override fun <A : Any> Set(iface: String, propName: String, value: A) {
            throw DBusExecutionException("dbusmenu properties are read-only")
        }

        override fun GetAll(iface: String): Map<String, Variant<*>> = menuProperties(iface)

        private fun menuProperties(iface: String): Map<String, Variant<*>> {
            if (iface.isNotEmpty() && iface != DBUSMENU_IFACE) return emptyMap()
            return buildMap {
                put("Version", Variant(UInt32(3), "u"))
                put("Status", Variant("normal", "s"))
                put("TextDirection", Variant("ltr", "s"))
                put("IconThemePath", Variant(emptyList<String>(), "as"))
            }
        }
    }

    private companion object {
        const val DBUS_BUS = "org.freedesktop.DBus"
        const val DBUS_PATH = "/org/freedesktop/DBus"
        const val WATCHER_BUS = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
        const val SNI_IFACE = "org.kde.StatusNotifierItem"
        const val DBUSMENU_IFACE = "com.canonical.dbusmenu"
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val ICON_SIZE = 22
        const val DEFAULT_ID = "TrayApp"
    }
}
