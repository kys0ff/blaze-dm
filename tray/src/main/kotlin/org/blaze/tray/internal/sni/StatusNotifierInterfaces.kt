package org.blaze.tray.internal.sni

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Hand-written bindings for the StatusNotifierItem (SNI) tray protocol spoken by
 * KDE Plasma and other modern Linux desktops, plus the `com.canonical.dbusmenu`
 * menu protocol it delegates to. Only declarations live here; all wire behaviour
 * is provided by `SniTrayService`.
 *
 * Method names intentionally keep the D-Bus member casing — dbus-java reflects on
 * `Method.getName()` and has no rename annotation for methods.
 */

/** The tray item we export on our own bus name. */
@Suppress("FunctionName")
@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {

    /** Left click on the icon. */
    fun Activate(x: UInt32, y: UInt32)

    /** Middle click (we treat it like Activate). */
    fun SecondaryActivate(x: UInt32, y: UInt32)

    /**
     * Right click. Hosts that support dbusmenu show our [DBusMenu] themselves,
     * so responding here is only needed for hosts that expect the app to pop
     * its own menu — Plasma never relies on it.
     */
    fun ContextMenu(x: UInt32, y: UInt32)

    fun Scroll(delta: Int, orientation: String)
}

/** The watcher exported by the desktop's system-tray host. */
@Suppress("FunctionName")
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {

    /**
     * Registers our item. The service string is either a bus name or the
     * `busname:/object/path` form, which we use so the host does not need an
     * ObjectManager to discover the item path.
     */
    fun RegisterStatusNotifierItem(service: String)
}

/** The context menu exported at `/MenuBar` and referenced by the item's `Menu` property. */
@Suppress("FunctionName")
@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenu : DBusInterface {

    /**
     * Layout of the menu, starting at [parentId] (root id is 0). Out args per spec:
     * `u revision` + `(ia{sv}av) layout` — one recursive struct of
     * (id, properties, children), each child variant holding the same struct shape.
     */
    fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): GetLayoutTuple<UInt32, MenuLayoutStruct>

    /** The host reports an interaction ("clicked", "hovered", ...) on an item. */
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    /**
     * Called before the menu is opened; returning `true` tells the host to
     * re-fetch [GetLayout]. We always return `true`, which keeps labels fresh
     * without emitting `LayoutUpdated` signals.
     */
    fun AboutShow(): Boolean

    fun AboutToShow(id: Int): Boolean

    /** A single property of a single item. */
    fun GetProperty(id: Int, name: String): Variant<*>

    /** Properties for a batch of items, returns `a(ia{sv})`. */
    fun GetGroupProperties(
        ids: List<Int>,
        propertyNames: List<String>,
    ): List<MenuPropertiesStruct>

    /** The host may ask us to change a property; all our properties are read-only. */
    fun SetProperty(property: String, value: Variant<*>)

    fun SetGroupProperty(id: Int, property: String, value: Variant<*>)

    /** Like [Event] but purely informational ("opened", "closed"). */
    fun SendEvent(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
}

/**
 * Multi-value return carrier — dbus-java serialises the `@Position`-annotated
 * fields (0-based) as the method's out arguments, using the type arguments
 * declared on the interface method for the signatures.
 */
class GetLayoutTuple<T1, T2>(
    @field:Position(0) val revision: T1,
    @field:Position(1) val layout: T2,
) : Tuple()

/**
 * One node of the `GetLayout` tree: `(ia{sv}av)` — item id, its properties, and
 * one variant per child item holding a nested [MenuLayoutStruct].
 */
class MenuLayoutStruct(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

/**
 * One `GetGroupProperties` element (`(ia{sv})`). Unlike [Tuple]s, which expand
 * to several out-arguments and are rejected inside arrays by dbus-java, a
 * concrete [Struct] serialises as a single struct signature, so hosts can
 * receive `a(ia{sv})`.
 */
class MenuPropertiesStruct(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()
