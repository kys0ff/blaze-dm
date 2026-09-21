package org.blaze.platform.autostart

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the XDG autostart entry handling against a temp directory, so the real
 * user config dir is never touched.
 */
class LinuxAutoStartServiceTest {

    private val tempDir = Files.createTempDirectory("blaze-autostart-test")
    private val service = LinuxAutoStartService(tempDir) { "/opt/Blaze/bin/blaze" }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun startsDisabled() {
        assertTrue(service.isSupported)
        assertFalse(service.isEnabled())
    }

    @Test
    fun enableWritesAWellFormedDesktopEntry() {
        service.enable().getOrThrow()

        val file = tempDir.resolve("org.blaze.desktop")
        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertTrue(content.startsWith("[Desktop Entry]"), "must start with the desktop-entry header")
        assertTrue("Exec=/opt/Blaze/bin/blaze" in content, "Exec must carry the resolved command:\n$content")
        assertTrue("X-GNOME-Autostart-enabled=true" in content)
        assertTrue(service.isEnabled())
    }

    @Test
    fun disableRemovesTheEntry() {
        service.enable().getOrThrow()
        assertTrue(service.isEnabled())

        service.disable().getOrThrow()

        assertFalse(service.isEnabled())
        assertFalse(Files.exists(tempDir.resolve("org.blaze.desktop")))
    }

    @Test
    fun disableIsIdempotentWhenNothingWasRegistered() {
        service.disable().getOrThrow() // must succeed quietly

        assertFalse(service.isEnabled())
    }

    @Test
    fun enableCreatesTheAutostartDirectoryWhenMissing() {
        val missingDir = tempDir.resolve("nested/autostart")

        LinuxAutoStartService(missingDir) { "blaze" }.enable().getOrThrow()

        assertTrue(Files.exists(missingDir.resolve("org.blaze.desktop")))
    }
}
