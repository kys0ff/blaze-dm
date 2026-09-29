package org.blaze.notifications.api

/**
 * Gate the embedding app plugs into [NotificationPublisher] to decide, at the moment
 * a notification would otherwise go out, whether the user can already see the app.
 * A typical implementation is "window hidden or unfocused".
 *
 * Receives the [spec] so severity can override the check (an app may let CRITICAL
 * notifications through even while it is in sight).
 */
fun interface NotificationGate {
    companion object {
        /** The permissive default: every notification goes through. */
        val OPEN = NotificationGate { true }
    }

    /** True when [notification] may be posted to the desktop right now. */
    fun shouldDeliver(notification: NotificationSpec): Boolean
}

/**
 * Convenience facade over a [service] that consults [gate] before every send, so
 * apps wire "notify only when I'm not looking" once and forget about it.
 *
 * Like the service, it never throws: an unsupported backend simply drops requests.
 */
class NotificationPublisher(
    val service: NotificationService,
    val gate: NotificationGate = NotificationGate.OPEN,
) {
    val isSupported: Boolean get() = service.isSupported

    /** Posts [notification] if the gate allows it and the backend supports it. */
    suspend fun publish(notification: NotificationSpec) {
        if (!service.isSupported) return
        if (!gate.shouldDeliver(notification)) return
        service.show(notification)
    }
}
