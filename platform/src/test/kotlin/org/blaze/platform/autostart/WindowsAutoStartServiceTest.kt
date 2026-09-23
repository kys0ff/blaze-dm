package org.blaze.platform.autostart

import org.blaze.platform.TEST_IDENTITY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read side of the per-user `Run` key: [WindowsAutoStartService.registrationCommand] shells out to
 * `reg.exe`, so what is testable off Windows - and what the pruning verdict actually rests on - is the
 * parsing of that tool's output. Registry mutation itself is deliberately not simulated here; a wrong
 * `reg add`/`reg delete` vector would be caught on the platform it belongs to.
 */
class WindowsAutoStartServiceTest {

    @Test
    fun parseRunValueFindsOurValueAmongTheOtherLoginItems() {
        val output = """

            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
                OneDrive    REG_SZ    C:\Users\me\AppData\Local\Microsoft\OneDrive\OneDrive.exe /background
                Blaze    REG_SZ    C:\Program Files\Blaze\bin\Blaze.exe
                Steam    REG_EXPAND_SZ    "C:\Program Files (x86)\Steam\steam.exe" -silent

            End of search: 3 piece(s) found.
        """.trimIndent()

        assertEquals(
            """C:\Program Files\Blaze\bin\Blaze.exe""",
            WindowsAutoStartService.parseRunValue(output, "Blaze"),
        )
        // Values are matched by name, case-insensitively, and the data keeps its own quoting.
        assertEquals(
            "\"C:\\Program Files (x86)\\Steam\\steam.exe\" -silent",
            WindowsAutoStartService.parseRunValue(output, "steam"),
        )
    }

    @Test
    fun parseRunValueReadsAMissingValueAsNothingRegistered() {
        val error = """
            ERROR: The system was unable to find the specified registry key, value, or data entry point.
        """.trimIndent()

        assertNull(WindowsAutoStartService.parseRunValue(error, "Blaze"))
        assertNull(WindowsAutoStartService.parseRunValue("", "Blaze"))
        assertNull(
            WindowsAutoStartService.parseRunValue("    Other    REG_SZ    C:\\other.exe", "Blaze"),
            "another app's value is not ours to read",
        )
        assertNull(
            WindowsAutoStartService.parseRunValue("    Blaze    REG_SZ    ", "Blaze"),
            "an empty value launches nothing and says nothing",
        )
    }

    @Test
    fun parseRunValueHandlesAValueNameContainingSpaces() {
        val output = """    Blaze Downloader    REG_SZ    C:\Blaze\bin\Blaze.exe"""

        assertEquals(
            """C:\Blaze\bin\Blaze.exe""",
            WindowsAutoStartService.parseRunValue(output, "Blaze Downloader"),
            "the identity's app name is arbitrary text; reg pads with spaces",
        )
    }

    @Test
    fun enableRefusesADevelopmentLaunchBeforeTouchingTheRegistry() {
        // The refusal happens in argument validation, so no reg.exe process is ever spawned - which is
        // also why this test is meaningful on a Linux host.
        val service = WindowsAutoStartService(TEST_IDENTITY) { "/home/dev/.jdks/jbr-21/bin/java" }

        val failure = service.enable().exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "a debug JVM must not become a login item: $failure")
    }
}
