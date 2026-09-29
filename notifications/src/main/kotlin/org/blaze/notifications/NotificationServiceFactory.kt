package org.blaze.notifications

import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.internal.UnsupportedNotificationService
import org.blaze.notifications.internal.freedesktop.FreedesktopNotificationService
import org.blaze.notifications.internal.macos.MacOsNotificationService
import org.blaze.notifications.internal.windows.WindowsNotificationService

/**
 * Picks the notification backend for the host platform, exactly like the tray's
 * service factory does: Linux desktops get the Freedesktop Notifications D-Bus
 * protocol, macOS the `osascript` bridge, Windows the PowerShell balloon helper -
 * anything else (or a broken session) degrades to a silent no-op service rather
 * than failing, so callers only ever see the [NotificationService] abstraction.
 */
object NotificationServiceFactory {

    fun create(
        appName: String,
        appIcon: String = "",
        osName: String = System.getProperty("os.name"),
    ): NotificationService {
        val name = osName.lowercase()
        return when {
            name.contains("linux") || name.contains("bsd") -> FreedesktopNotificationService(appName, appIcon)
            name.contains("mac") || name.contains("darwin") -> MacOsNotificationService()
            name.contains("windows") -> WindowsNotificationService()
            else -> UnsupportedNotificationService
        }
    }
}
