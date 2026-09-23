package org.blaze.osintegration.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests over the exact `reg add` argument vectors (the commands themselves cannot run on this
 * host - they are *configuration verified*, not executed). The invariants that matter for security
 * and for "magnet clicks just work" are all visible in the vectors: one argument per field, quoted
 * executable, the URI handed over as one `"%1"` slot.
 */
class WindowsUrlSchemeRegistrarTest {

    @Test
    fun `registration is exactly four idempotent reg add writes`() {
        val commands = WindowsUrlSchemeRegistrar.buildCommands(
            scheme = "magnet",
            displayName = "Magnet BitTorrent Link",
            executable = """C:\Program Files\Blaze\bin\Blaze.exe""",
        )
        assertEquals(4, commands.size)
        commands.forEach { args ->
            assertEquals(listOf("reg", "add"), args.take(2), "each write is a discrete reg add, never a shell line")
        }
        val roots = commands.map { it[2] }
        assertEquals(
            listOf(
                """HKCU\Software\Classes\magnet""",
                """HKCU\Software\Classes\magnet""",
                """HKCU\Software\Classes\magnet\DefaultIcon""",
                """HKCU\Software\Classes\magnet\shell\open\command""",
            ),
            roots,
            "per-user classes only: no HKLM, no admin",
        )
    }

    @Test
    fun `the open command passes the whole URI as one quoted argument to a spaced executable`() {
        val commands = WindowsUrlSchemeRegistrar.buildCommands(
            scheme = "magnet",
            displayName = "Magnet",
            executable = """C:\Users\Ü ser\Blaze\bin\Blaze.exe""",
        )
        val command = commands.last() // reg add <root>\shell\open\command /ve /d <value> /f
        val value = command[command.indexOf("/d") + 1]
        assertEquals(
            "\"C:\\Users\\Ü ser\\Blaze\\bin\\Blaze.exe\" \"%1\"",
            value,
            "quoted exe + the full URI as the single second argument",
        )
        assertTrue(value.startsWith("\""), "the executable path is quoted against spaces")
        assertTrue(value.endsWith("\"%1\""), "the URI arrives as one quoted argv slot")
        assertEquals("/f", command.last(), "writes are forced/idempotent")
        assertEquals("/ve", command[3])
    }

    @Test
    fun `unsafe scheme names are refused before any process is spawned`() {
        listOf("magnet;calc", "", "..\\..\\evil", "1bad", "a".repeat(64)).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                WindowsUrlSchemeRegistrar.buildCommands(bad, "x", "C:\\a.exe")
            }
        }
    }

    @Test
    fun `ensureRegistered is a no-op off Windows and on refusal, never an error`() {
        assertFalse(WindowsUrlSchemeRegistrar.ensureRegistered("magnet", "M", "/usr/bin/blaze", isWindows = false))
        assertFalse(WindowsUrlSchemeRegistrar.ensureRegistered("not a scheme", "M", "/usr/bin/blaze", isWindows = true))
    }

    @Test
    fun `the scheme regex accepts the shapes real protocols have`() {
        // sanity on the accepted side too (magnet itself, plus plausible future registrations)
        assertTrue("magnet".matches(Regex("[A-Za-z][A-Za-z0-9+.-]{0,31}")))
        assertTrue("blaze-dl".matches(Regex("[A-Za-z][A-Za-z0-9+.-]{0,31}")))
    }

    // ── the uninstall counterpart ───────────────────────────────────────────────────────────────

    @Test
    fun `unregistering deletes the whole per-user scheme key in one forced write`() {
        assertEquals(
            listOf("reg", "delete", """HKCU\Software\Classes\magnet""", "/f"),
            WindowsUrlSchemeRegistrar.buildUnregisterCommand("magnet"),
            "values and sub-keys go together; HKCU only, so no elevation is requested",
        )
        assertFailsWith<IllegalArgumentException> {
            WindowsUrlSchemeRegistrar.buildUnregisterCommand("magnet&calc")
        }
    }

    @Test
    fun `the registered open command is read out of reg query output`() {
        val output = """
            HKEY_CURRENT_USER\Software\Classes\magnet\shell\open\command
                (Default)    REG_SZ    "C:\Program Files\Blaze\bin\Blaze.exe" "%1"

            End of search: 1 piece(s) found.
        """.trimIndent()

        assertEquals(
            "\"C:\\Program Files\\Blaze\\bin\\Blaze.exe\" \"%1\"",
            WindowsUrlSchemeRegistrar.parseOpenCommand(output),
        )
        assertEquals(
            "/opt/Blaze/bin/Blaze %1",
            WindowsUrlSchemeRegistrar.parseOpenCommand("    \"(Default)\"    REG_SZ    /opt/Blaze/bin/Blaze %1"),
            "some locales quote the value name",
        )
        assertNull(
            WindowsUrlSchemeRegistrar.parseOpenCommand("ERROR: The system was unable to find the specified registry key."),
            "an unregistered scheme reads as nothing there, which is already clean",
        )
        assertNull(WindowsUrlSchemeRegistrar.parseOpenCommand(""))
    }

    @Test
    fun `only a handler Blaze itself wrote is ever unregistered`() {
        assertTrue(
            WindowsUrlSchemeRegistrar.isOwnHandler("\"C:\\Program Files\\Blaze\\bin\\Blaze.exe\" \"%1\"", "Blaze"),
            "its own registration, spaces and all",
        )
        assertTrue(WindowsUrlSchemeRegistrar.isOwnHandler("""C:\blaze\bin\blaze.exe""", "Blaze"), "case and .exe ignored")
        assertTrue(WindowsUrlSchemeRegistrar.isOwnHandler("\"/opt/Blaze/bin/Blaze\" %u", "Blaze"))
        assertFalse(
            WindowsUrlSchemeRegistrar.isOwnHandler("\"C:\\Program Files\\uTorrent\\uTorrent.exe\"  \"%1\"", "Blaze"),
            "a magnet claim the user handed to another player is not Blaze's to steal back",
        )
        assertFalse(WindowsUrlSchemeRegistrar.isOwnHandler("""C:\Windows\System32\cmd.exe /c start""", "Blaze"))
        assertFalse(WindowsUrlSchemeRegistrar.isOwnHandler("\"\"", "Blaze"), "an empty program names nothing")
    }

    @Test
    fun `a missing target is the difference between an uninstall and a healthy install`() {
        val live = Files.createTempFile("blaze-handler", ".exe")
        try {
            assertTrue(WindowsUrlSchemeRegistrar.isMissingTarget("\"/opt/Blaze-0.9.0/bin/Blaze\" \"%1\""))
            assertFalse(WindowsUrlSchemeRegistrar.isMissingTarget("\"$live\" \"%1\""), "still installed")
            assertFalse(WindowsUrlSchemeRegistrar.isMissingTarget("blaze"), "a relative program proves nothing")
        } finally {
            Files.deleteIfExists(live)
        }
    }

    @Test
    fun `unregister never shells out to the registry tool on another platform`() {
        assertFalse(
            WindowsUrlSchemeRegistrar.unregister("magnet", "Blaze", staleOnly = false, isWindows = false),
            "nothing was removed, and nothing was run",
        )
    }
}
