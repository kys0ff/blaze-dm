package org.blaze.osintegration.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
