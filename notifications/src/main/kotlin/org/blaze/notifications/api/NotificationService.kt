package org.blaze.notifications.api

/**
 * Contract for showing desktop-native notifications (toast / banner / notification
 * center) on the host platform.
 *
 * Implementations must degrade gracefully, exactly like the tray's tray-service
 * contract does: where the desktop offers no notification surface, [isSupported] is
 * false and [show] is a no-op, so callers never need platform checks of their own.
 *
 * The library carries no application knowledge: the app name and icon identity
 * are supplied through the factory / DI wiring by the embedding app.
 */
interface NotificationService {
    /** Stable identifier of the chosen transport ("freedesktop", "osascript", ...). */
    val backendId: String

    /** Whether this platform/environment can actually deliver notifications. */
    val isSupported: Boolean

    /**
     * Post [notification] to the desktop and return once it has been handed to the
     * notification surface (not once the user has seen it). Suspending because the
     * transports block: a round trip on the session bus, or a spawned helper
     * process. Failures inside supported backends are swallowed and logged -
     * a notification is never worth a crash.
     */
    suspend fun show(notification: NotificationSpec)

    /** Release the transport (D-Bus connection etc). Safe to call repeatedly. */
    fun dispose()
}
