package org.blaze.notifications.internal

import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.api.NotificationSpec

/**
 * Backend for platforms (or broken sessions) with no notification surface: everything
 * reports unsupported and every send is dropped, so callers need no null checks.
 */
object UnsupportedNotificationService : NotificationService {
    override val backendId: String = "unsupported"
    override val isSupported: Boolean = false
    override suspend fun show(notification: NotificationSpec) = Unit
    override fun dispose() = Unit
}
