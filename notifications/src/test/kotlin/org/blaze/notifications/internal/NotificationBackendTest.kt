package org.blaze.notifications.internal

import org.blaze.notifications.api.NotificationSpec
import org.blaze.notifications.api.NotificationUrgency
import org.blaze.notifications.internal.freedesktop.FreedesktopNotificationService
import org.blaze.notifications.internal.freedesktop.FreedesktopNotifications
import org.blaze.notifications.internal.macos.MacOsNotificationService
import org.blaze.notifications.internal.windows.WindowsNotificationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three transports differ, but the risky, copy-able part they all share is embedding
 * user/file text into a shell/AppleScript/PowerShell payload. These tests pin each
 * builder's escaping and severity mapping as pure functions - nothing is executed.
 */
class NotificationBackendTest {

    // ---- Freedesktop ----

    @Test
    fun `freedesktop binding keeps the spec method-name casing`() {
        // dbus-java reflects Method.getName() for the wire member, so a lowercase Kotlin
        // `notify` would be sent as member `notify` and rejected with UnknownMethod - the
        // exact bug that silenced every notification. Pin the capitalised names.
        val names = FreedesktopNotifications::class.java.methods.map { it.name }
        assertTrue("Notify" in names, "wire member must be capital-N Notify, got $names")
        assertTrue("CloseNotification" in names)
        assertTrue("GetCapabilities" in names)
        assertTrue("notify" !in names, "a lowercase notify would go on the wire unchanged")
    }

    @Test
    fun `freedesktop maps urgency to the spec byte`() {
        assertEquals(0.toByte(), FreedesktopNotificationService.urgencyByte(NotificationUrgency.LOW))
        assertEquals(1.toByte(), FreedesktopNotificationService.urgencyByte(NotificationUrgency.NORMAL))
        assertEquals(2.toByte(), FreedesktopNotificationService.urgencyByte(NotificationUrgency.CRITICAL))
    }

    @Test
    fun `freedesktop emits an urgency hint as a byte variant`() {
        val service = FreedesktopNotificationService(appName = "Test")
        val hints = service.buildHints(NotificationUrgency.CRITICAL)
        val variant = hints.getValue("urgency")
        assertEquals("y", variant.sig)
        assertEquals(2.toByte(), variant.value)
    }

    // ---- macOS / AppleScript ----

    @Test
    fun `applescript quotes backslash, quote and newline`() {
        assertEquals(
            "\"C:\\\\path\\\\to\\\"file\\\"\\rline2\"",
            MacOsNotificationService.appleScriptString("C:\\path\\to\"file\"\nline2"),
        )
    }

    @Test
    fun `applescript statement carries title and body`() {
        val line = MacOsNotificationService.scriptLine(NotificationSpec("Saved", "movie.mkv"))
        assertEquals("display notification \"movie.mkv\" with title \"Saved\"", line)
    }

    @Test
    fun `an empty body becomes a space so applescript still parses`() {
        val line = MacOsNotificationService.scriptLine(NotificationSpec("Saved", ""))
        assertTrue("\" \"" in line, line)
    }

    // ---- Windows / PowerShell ----

    @Test
    fun `powershell doubles apostrophes and folds newlines`() {
        assertEquals("'it''s fine'", WindowsNotificationService.powerShellString("it's\nfine"))
    }

    @Test
    fun `powershell only elevates the balloon icon for critical`() {
        assertEquals("Info", WindowsNotificationService.tooltipIcon(NotificationUrgency.LOW))
        assertEquals("Info", WindowsNotificationService.tooltipIcon(NotificationUrgency.NORMAL))
        assertEquals("Warning", WindowsNotificationService.tooltipIcon(NotificationUrgency.CRITICAL))
    }

    @Test
    fun `powershell script embeds title and text and disposes the icon`() {
        val script = WindowsNotificationService.script(NotificationSpec("Done", "a'b"), timeoutMs = 5_000)
        assertTrue("BalloonTipTitle = 'Done'" in script, script)
        assertTrue("BalloonTipText = 'a''b'" in script, script)
        assertTrue("ShowBalloonTip(5000)" in script, script)
        assertTrue(script.trimEnd().endsWith("\$icon.Dispose()"), script)
    }
}
