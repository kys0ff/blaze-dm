package org.blaze.platform.desktop

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The policy that keeps a development launch out of the user's desktop session. Each accepted shape is
 * something a login manager can run on its own; each refused one is what `ProcessHandle` reports while
 * Blaze is started from an IDE or `gradle run` - a command that would fail at every sign-in.
 */
class LaunchCommandTest {

    @Test
    fun `a packaged launcher is standalone`() {
        assertTrue(LaunchCommand.isStandaloneExecutable("/opt/Blaze/bin/Blaze"))
        assertTrue(LaunchCommand.isStandaloneExecutable("\"/home/user/My Apps/Blaze\" %F"), "quoted, spaces, field code")
        assertTrue(LaunchCommand.isStandaloneExecutable("""C:\Program Files\Blaze\bin\Blaze.exe"""))
        assertTrue(LaunchCommand.isStandaloneExecutable("blaze"), "PATH-relative: /usr/bin launchers are legitimate")
    }

    @Test
    fun `an interpreter alone is never a login item`() {
        assertFalse(LaunchCommand.isStandaloneExecutable("/home/user/.jdks/jbr-21/bin/java"), "the debug-run JVM")
        assertFalse(LaunchCommand.isStandaloneExecutable("/usr/lib/jvm/java-21/bin/java -cp /build/classes org.blaze.MainKt"))
        assertFalse(LaunchCommand.isStandaloneExecutable("javaw.exe"))
        assertFalse(LaunchCommand.isStandaloneExecutable("/bin/sh -c blaze"), "an interpreter needs arguments we do not have")
    }

    @Test
    fun `a development command line is refused even behind a real binary`() {
        // Someone can rename/symlink the JDK; the class-path shape is what gives it away.
        assertFalse(LaunchCommand.isStandaloneExecutable("/opt/Blaze/bin/Blaze -classpath /build/classes"))
        assertFalse(LaunchCommand.isStandaloneExecutable("/opt/Blaze/bin/Blaze --module-path /mods --add-opens java.base/java.lang=ALL-UNNAMED"))
        assertFalse(LaunchCommand.isStandaloneExecutable("java -cp /build org.blaze.MainKt"))
    }

    @Test
    fun `an empty command launches nothing`() {
        assertFalse(LaunchCommand.isStandaloneExecutable(""))
        assertFalse(LaunchCommand.isStandaloneExecutable("   "))
    }

    @Test
    fun `a gone launcher under a stable root reads as an uninstall`() {
        assertTrue(LaunchCommand.isMissingInstall("/opt/Blaze-0227/bin/Blaze"), "the install directory no longer exists")
        assertTrue(LaunchCommand.isMissingInstall("\"/opt/Blaze-0227/bin/Blaze\" %F"))
    }

    @Test
    fun `transient and unresolvable commands are never treated as an uninstall`() {
        assertFalse(
            LaunchCommand.isMissingInstall("/tmp/.mount_BlazeQk1z2f/Blaze"),
            "an AppImage mount is expected to vanish between runs",
        )
        assertFalse(LaunchCommand.isMissingInstall("/tmp/blaze-does-not-exist/Blaze"), "a scratch tree proves nothing")
        assertFalse(LaunchCommand.isMissingInstall("blaze"), "resolving a PATH-relative command means guessing the session PATH")
        assertFalse(LaunchCommand.isMissingInstall(""), "nothing registered is not a broken registration")
    }

    @Test
    fun `an existing launcher is not missing`() {
        val live = Files.createTempFile("blaze-launcher", ".exe")
        try {
            // Under the temp dir the ephemeral check short-circuits first, so assert the command form
            // is accepted as standalone *and* not reported as a removal.
            assertTrue(LaunchCommand.isStandaloneExecutable(live.toString()))
            assertFalse(LaunchCommand.isMissingInstall(live.toString()))
        } finally {
            Files.deleteIfExists(live)
        }
    }

    @Test
    fun `the executable of a command drops quoting and arguments`() {
        assertEquals(Paths.get("/opt/Blaze/bin/Blaze"), LaunchCommand.executableOf("\"/opt/Blaze/bin/Blaze\" %F"))
        assertEquals(Paths.get("/opt/Blaze/bin/Blaze"), LaunchCommand.executableOf("/opt/Blaze/bin/Blaze --minimized"))
        assertEquals(
            Paths.get("""C:\Program Files\Blaze\bin\Blaze.exe"""),
            LaunchCommand.executableOf("\"C:\\Program Files\\Blaze\\bin\\Blaze.exe\" \"%1\""),
            "the Windows open-command shape Blaze registers",
        )
    }
}
