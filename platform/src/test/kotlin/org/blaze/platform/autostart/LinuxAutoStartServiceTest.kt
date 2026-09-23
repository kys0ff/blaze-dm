package org.blaze.platform.autostart

import org.blaze.platform.TEST_IDENTITY
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the XDG autostart entry handling against a temp directory, so the real
 * user config dir is never touched.
 */
class LinuxAutoStartServiceTest {

    private val tempDir = Files.createTempDirectory("blaze-autostart-test")
    private val service = LinuxAutoStartService(TEST_IDENTITY, tempDir) { "/opt/Blaze/bin/blaze" }

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
        assertTrue("Name=Blaze" in content, "entry must carry the identity's app name:\n$content")
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

        LinuxAutoStartService(TEST_IDENTITY, missingDir) { "blaze" }.enable().getOrThrow()

        assertTrue(Files.exists(missingDir.resolve("org.blaze.desktop")))
    }

    @Test
    fun enableRefusesTheJvmOfADevelopmentRun() {
        // ProcessHandle reports the bare JDK under `gradle run`/an IDE run; registering it would fail
        // at every sign-in for a session the user long finished.
        val devRun = LinuxAutoStartService(TEST_IDENTITY, tempDir) { "/home/user/.jdks/jbr-21/bin/java" }

        val failure = devRun.enable().exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "a development launch must never become a login item: $failure")
        assertTrue("development launch" in failure.message.orEmpty(), "the message says why:\n${failure.message}")
        assertFalse(Files.exists(tempDir.resolve("org.blaze.desktop")), "nothing at all is written")
        assertFalse(devRun.isEnabled())
    }

    @Test
    fun registrationCommandReadsBackTheRecordedExec() {
        assertFalse(service.isEnabled())
        assertNull(service.registrationCommand(), "nothing registered means nothing to prune")

        service.enable().getOrThrow()

        assertEquals("/opt/Blaze/bin/blaze", service.registrationCommand())

        service.disable().getOrThrow()
        assertNull(service.registrationCommand())
    }

    @Test
    fun registrationCommandOfAForeignFileSurvivesGracefully() {
        // A hand-written or third-party entry at our path is still read, never deleted here.
        val dir = tempDir.resolve("foreign")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("org.blaze.desktop"),
            "[Desktop Entry]\nType=Application\nExec=/opt/other/bin/other\n",
        )

        assertEquals("/opt/other/bin/other", LinuxAutoStartService(TEST_IDENTITY, dir).registrationCommand())
    }
}
