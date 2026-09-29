package org.blaze.notifications

import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.internal.UnsupportedNotificationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * The factory is the module's only platform branch, so its table is pinned here with
 * an injected os.name - no backend may touch the real OS during a test, which the
 * lazy support probes guarantee (constructing a backend must never open a transport).
 */
class NotificationServiceFactoryTest {

    private fun backendFor(osName: String): NotificationService =
        NotificationServiceFactory.create(appName = "Test", appIcon = "test-icon", osName = osName)

    @Test
    fun `linux picks the freedesktop dbus backend`() {
        assertEquals("freedesktop", backendFor("Linux").backendId)
        assertEquals("freedesktop", backendFor("GNU/Linux").backendId)
    }

    @Test
    fun `bsd flavours also speak freedesktop notifications`() {
        assertEquals("freedesktop", backendFor("FreeBSD").backendId)
    }

    @Test
    fun `macos picks the osascript backend`() {
        assertEquals("osascript", backendFor("Mac OS X").backendId)
    }

    @Test
    fun `windows picks the powershell backend`() {
        assertEquals("powershell", backendFor("Windows 11").backendId)
    }

    @Test
    fun `unknown platforms degrade to the silent backend`() {
        assertEquals("unsupported", backendFor("Haiku").backendId)
        assertSame(UnsupportedNotificationService, backendFor("Haiku"))
        assertFalse(UnsupportedNotificationService.isSupported)
    }
}
