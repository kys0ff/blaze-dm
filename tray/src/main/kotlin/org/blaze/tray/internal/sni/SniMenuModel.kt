package org.blaze.tray.internal.sni

import org.blaze.tray.api.TrayMenuItem
import org.freedesktop.dbus.types.Variant

/** One row of the tray context menu, flattened from a [TrayMenuItem]. */
internal data class SniMenuEntry(
    val id: Int,
    val label: String,
    val enabled: Boolean = true,
    val isSeparator: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * Pure menu-state holder for the SNI tray: knows the current entries and how to
 * render them into the `a{sv}` layout dicts of `com.canonical.dbusmenu`. No D-Bus
 * plumbing lives here, so the whole menu behavior is unit-testable.
 *
 * Ids are positional (1-based, in menu order) and stable while the installed
 * [TrayConfig.menu] shape does not change, because hosts cache layouts and
 * report clicks by id.
 */
internal class SniMenuModel {

    @Volatile
    var entries: List<SniMenuEntry> = emptyList()
        private set

    fun rebuild(menu: List<TrayMenuItem>, windowVisible: Boolean) {
        entries = menu.mapIndexed { index, item ->
            val id = index + 1
            when (item) {
                TrayMenuItem.Separator -> SniMenuEntry(
                    id = id,
                    label = "",
                    isSeparator = true,
                )
                is TrayMenuItem.Item -> SniMenuEntry(
                    id = id,
                    label = item.label,
                    enabled = item.enabled,
                    onClick = item.onClick,
                )
                is TrayMenuItem.WindowToggle -> SniMenuEntry(
                    id = id,
                    label = item.labelFor(windowVisible),
                    onClick = item.onClick,
                )
            }
        }
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
    }
}
