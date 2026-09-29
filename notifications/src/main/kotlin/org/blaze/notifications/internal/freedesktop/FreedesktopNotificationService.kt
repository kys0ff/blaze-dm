package org.blaze.notifications.internal.freedesktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.api.NotificationSpec
import org.blaze.notifications.api.NotificationUrgency
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.util.Collections

/**
 * Freedesktop Notifications backend - the D-Bus protocol every Linux desktop
 * (KDE Plasma, GNOME, XFCE, ...) implements for `notify-send`.
 *
 * The connection is deliberately **private** (`withShared(false)`): dbus-java caches
 * one reference-counted session connection per address by default, so a shared
 * connection would tie this service's lifecycle to any other D-Bus user in the app
 * (tray, taskbar progress) and survive its dispose. A private connection keeps the
 * notification transport fully isolated.
 *
 * Support is probed lazily and cached: opening the bus and resolving the remote
 * `Notify` object happens on the first [isSupported] / [show] call, so constructing
 * this service on a machine without a session bus costs nothing and simply reports
 * itself unsupported forever after (until [dispose] resets the probe).
 *
 * Every entry point degrades quietly: a failed send is logged at warn and dropped -
 * a notification is never worth bothering the app for.
 */
class FreedesktopNotificationService(
    /** Surfaced as the protocol's `app_name`; desktops use it for the "from" label. */
    private val appName: String,
    /** Icon name or absolute path in the `icon` field; "" lets the desktop decide. */
    private val appIcon: String = "",
    /** Banner lifetime in ms; -1 delegates to the notification server's default. */
    private val expireTimeoutMs: Int = -1,
) : NotificationService {

    private val logger = LoggerFactory.getLogger(FreedesktopNotificationService::class.java)

    override val backendId: String = "freedesktop"

    private val lock = Any()
    private var connection: DBusConnection? = null
    private var notifier: FreedesktopNotifications? = null
    private var supportedCache: Boolean? = null

    override val isSupported: Boolean
        get() = synchronized(lock) {
            supportedCache ?: runCatching { open() }.isSuccess.also {
                supportedCache = it
                if (!it) closeConnection()
            }
        }

    override suspend fun show(notification: NotificationSpec) {
        if (!isSupported) return
        withContext(Dispatchers.IO) {
            runCatching {
                val target = synchronized(lock) { notifier } ?: return@runCatching
                target.Notify(
                    appName,
                    UInt32(0), // never replacing an earlier notification
                    appIcon,
                    notification.title,
                    notification.body,
                    Collections.emptyList(),
                    buildHints(notification.urgency),
                    expireTimeoutMs,
                )
            }.onFailure { logger.warn("Failed to post a Freedesktop notification", it) }
        }
    }

    override fun dispose() {
        synchronized(lock) { closeConnection() }
    }

    private fun open(): DBusConnection = synchronized(lock) {
        connection ?: DBusConnectionBuilder.forSessionBus().withShared(false).build()
            .also {
                connection = it
                notifier = it.getRemoteObject(
                    NOTIFICATIONS_SERVICE, NOTIFICATIONS_PATH, FreedesktopNotifications::class.java,
                )
            }
    }

    private fun closeConnection() {
        val conn = connection ?: return
        runCatching { conn.disconnect() }
            .onFailure { logger.warn("Failed to close the notifications D-Bus connection", it) }
        connection = null
        notifier = null
        supportedCache = null
    }

    /**
     * The `a{sv}` hints dict. `urgency` is the byte the spec defines (0 low,
     * 1 normal, 2 critical); servers that ignore it simply show a normal banner.
     */
    internal fun buildHints(urgency: NotificationUrgency): Map<String, Variant<*>> =
        mapOf("urgency" to Variant(urgencyByte(urgency), "y"))

    companion object {
        const val NOTIFICATIONS_SERVICE = "org.freedesktop.Notifications"
        const val NOTIFICATIONS_PATH = "/org/freedesktop/Notifications"

        /** Spec mapping, kept pure so tests can pin it without a bus. */
        fun urgencyByte(urgency: NotificationUrgency): Byte = when (urgency) {
            NotificationUrgency.LOW -> 0
            NotificationUrgency.NORMAL -> 1
            NotificationUrgency.CRITICAL -> 2
        }
    }
}

/**
 * Handwritten binding for `org.freedesktop.Notifications` (spec version 1.2).
 *
 * Method names keep the wire casing because dbus-java reflects on `Method.getName()`
 * to derive the D-Bus member - `Notify` is spelled with its capital N for exactly that
 * reason (a lowercase Kotlin `notify` would go on the wire as member `notify`, which
 * the daemon rejects with UnknownMethod). [Notify]'s parameter types are exactly the
 * wire signature `(susssasa{sv}i)` - the empty action list is `as`, the hints are
 * `a{sv}`, and the `u` fields use [UInt32] (dbus-java derives `x` from a plain
 * `Long`, which would put the wrong signature on the wire). The protocol returns
 * the new notification id as `u`; it is kept because apps that want to replace a
 * banner later (progress updates) need to hold on to it.
 */
@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.Notifications")
interface FreedesktopNotifications : DBusInterface {

    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    fun CloseNotification(id: UInt32)

    fun GetCapabilities(): List<String>
}
