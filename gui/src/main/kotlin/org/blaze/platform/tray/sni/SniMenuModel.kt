package org.blaze.platform.tray.sni

import org.blaze.platform.tray.TrayLabels
import org.freedesktop.dbus.types.Variant

/** Which [TrayActions] callback a menu click maps to. */
enum class TrayMenuAction { TOGGLE, PAUSE_ALL, RESUME_ALL, SEPARATOR, QUIT }

/** One row of the tray context menu. */
data class SniMenuEntry(
    val id: Int,
    val action: TrayMenuAction,
    val label: String,
    val enabled: Boolean = true,
) {
    val isSeparator: Boolean get() = action == TrayMenuAction.SEPARATOR
}

/**
 * Pure menu-state holder for the SNI tray: knows the current entries and how to
 * render them into the `a{sv}` layout dicts of `com.canonical.dbusmenu`. No D-Bus
 * plumbing lives here, so the whole menu behaviour is unit-testable.
 *
 * Ids are stable across rebuilds (see [SniMenuModel.ID_*]) because hosts cache
 * layouts and report clicks by id.
 */
class SniMenuModel {

    @Volatile
    var entries: List<SniMenuEntry> = emptyList()
        private set

    fun rebuild(labels: TrayLabels, windowVisible: Boolean) {
        entries = defaultMenuEntries(labels, windowVisible)
    }

    /** The clickable entry with this menu id, or null for unknown/separator ids. */
    fun entryFor(id: Int): SniMenuEntry? =
        entries.firstOrNull { it.id == id && !it.isSeparator }

    /** dbusmenu layout properties of a single item. */
    fun layoutProperties(entry: SniMenuEntry): Map<String, Variant<*>> = buildMap {
        put("type", Variant(if (entry.isSeparator) "separator" else "standard", "s"))
        if (!entry.isSeparator) {
            put("label", Variant(entry.label, "s"))
            put("enabled", Variant(entry.enabled, "b"))
            put("visible", Variant(true, "b"))
        }
    }

    /** One item as a `(ia{sv}av)` leaf struct (children live in the parent struct). */
    fun itemStruct(entry: SniMenuEntry, propertyNames: List<String>): MenuLayoutStruct {
        val props = layoutProperties(entry)
        val filtered = if (propertyNames.isEmpty()) props
        else props.filterKeys { it in propertyNames }
        return MenuLayoutStruct(entry.id, filtered + ("id" to Variant(entry.id, "i")), emptyList())
    }

    /** The root `(ia{sv}av)` struct whose children are the menu items. */
    fun rootStruct(propertyNames: List<String>, includeChildren: Boolean): MenuLayoutStruct {
        val children = if (includeChildren) {
            entries.map { entry -> Variant(itemStruct(entry, propertyNames), "(ia{sv}av)") }
        } else {
            emptyList()
        }
        return MenuLayoutStruct(ROOT_ID, mapOf("id" to Variant(ROOT_ID, "i")), children)
    }

    /** Single property lookup for `GetProperty`; id 0 only exposes its own id. */
    fun singleProperty(id: Int, name: String): Variant<*>? {
        if (id == ROOT_ID) return if (name == "id") Variant(ROOT_ID, "i") else null
        val entry = entries.firstOrNull { it.id == id } ?: return null
        return layoutProperties(entry)[name]
    }

    /** Properties dict for `GetGroupProperties` (`a(ia{sv})`). */
    fun groupProperties(id: Int): Map<String, Variant<*>> {
        if (id == ROOT_ID) return mapOf("id" to Variant(ROOT_ID, "i"))
        val entry = entries.firstOrNull { it.id == id } ?: return emptyMap()
        return layoutProperties(entry) + ("id" to Variant(id, "i"))
    }

    companion object {
        const val ROOT_ID = 0
        const val ID_TOGGLE = 1
        const val ID_PAUSE_ALL = 2
        const val ID_RESUME_ALL = 3
        const val ID_SEPARATOR = 4
        const val ID_QUIT = 5

        /** Menu order mirrors [org.blaze.platform.tray.AwtTrayService]. */
        fun defaultMenuEntries(labels: TrayLabels, windowVisible: Boolean): List<SniMenuEntry> =
            listOf(
                SniMenuEntry(ID_TOGGLE, TrayMenuAction.TOGGLE, if (windowVisible) labels.hide else labels.show),
                SniMenuEntry(ID_PAUSE_ALL, TrayMenuAction.PAUSE_ALL, labels.pauseAll),
                SniMenuEntry(ID_RESUME_ALL, TrayMenuAction.RESUME_ALL, labels.resumeAll),
                SniMenuEntry(ID_SEPARATOR, TrayMenuAction.SEPARATOR, ""),
                SniMenuEntry(ID_QUIT, TrayMenuAction.QUIT, labels.quit),
            )
    }
}
