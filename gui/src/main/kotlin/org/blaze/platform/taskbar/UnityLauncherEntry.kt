package org.blaze.platform.taskbar

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * Hand-written binding for the `com.canonical.Unity.LauncherEntry` desktop protocol.
 *
 * KDE Plasma's task manager does not export or query an object: it installs a
 * session-wide match rule for the `com.canonical.Unity.LauncherEntry` `Update`
 * signal (from any sender, any path) and reads the new values straight out of the
 * signal payload. So the only piece we need is the [Update] signal, whose second
 * argument is a `a{sv}` property **map carrying the values** (`progress`,
 * `progress-visible`, ...), not the `as` name-list from the original Unity spec that
 * Plasma's slot `(s, a{sv})` cannot coerce.
 *
 * [UnityLauncherEntry] is declared as a (never-exported) [DBusInterface] purely so
 * dbus-java can derive the signal's interface name from the enclosing type. Method
 * names keep the wire casing because dbus-java reflects on `Method.getName()`.
 */
@DBusInterfaceName("com.canonical.Unity.LauncherEntry")
interface UnityLauncherEntry : DBusInterface {

    /**
     * Notifies the desktop that the launcher [uri] changed, carrying the new values
     * in [properties] (a `a{sv}` map). Parameters must be `String` + `Map<String,
     * Variant<*>>` so the wire signature is `(s, a{sv})` - what Plasma binds to.
     * Must be a nested member of [UnityLauncherEntry]: dbus-java derives the
     * signal's interface and member name from the enclosing type.
     */
    class Update(
        objectPath: String,
        val uri: String,
        val properties: Map<String, Variant<*>>,
    ) : DBusSignal(objectPath, uri, properties)
}
