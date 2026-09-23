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
 * macOS LaunchAgent handling against a temp directory (the real ~/Library/LaunchAgents is never
 * touched), plus the plist reader that makes a stale registration detectable. The `launchctl` side of
 * autostart is intentionally not exercised: the plist on disk *is* the state loginwindow reads at the
 * next sign-in, so the file is the contract.
 */
class MacOsAutoStartServiceTest {

    private val tempDir = Files.createTempDirectory("blaze-launchagent-test")
    private val plistName = "${TEST_IDENTITY.launchAgentLabel}.plist"
    private val service = MacOsAutoStartService(TEST_IDENTITY, tempDir) { "/opt/Blaze/bin/Blaze" }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun enableWritesARunAtLoadAgentAndReadsItsProgramBack() {
        assertFalse(service.isEnabled())
        assertNull(service.registrationCommand(), "no plist means nothing to prune")

        service.enable().getOrThrow()

        val file = tempDir.resolve(plistName)
        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertTrue("<key>RunAtLoad</key>" in content && "<true/>" in content, "RunAtLoad is what starts it:\n$content")
        assertTrue("<string>${TEST_IDENTITY.launchAgentLabel}</string>" in content)
        assertTrue(service.isEnabled())
        assertEquals("/opt/Blaze/bin/Blaze", service.registrationCommand())
    }

    @Test
    fun disableRemovesTheAgentAndIsIdempotent() {
        service.enable().getOrThrow()

        service.disable().getOrThrow()

        assertFalse(service.isEnabled())
        assertFalse(Files.exists(tempDir.resolve(plistName)))
        service.disable().getOrThrow() // removing an absent plist is success, not an error
        assertNull(service.registrationCommand())
    }

    @Test
    fun enableRefusesTheJvmOfADevelopmentRun() {
        val devRun = MacOsAutoStartService(TEST_IDENTITY, tempDir) {
            "/Users/dev/Library/Java/JavaVirtualMachines/jbr-21/Contents/Home/bin/java"
        }

        val failure = devRun.enable().exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "a debug JVM must not become a login item: $failure")
        assertFalse(Files.exists(tempDir.resolve(plistName)), "nothing at all is written")
    }

    @Test
    fun parseProgramArgumentsTakesTheArrayNotTheLabel() {
        val plist = buildString {
            appendLine("<plist version=\"1.0\">")
            appendLine("<dict>")
            appendLine("\t<key>Label</key>")
            appendLine("\t<string>org.blaze.autostart</string>")
            appendLine("\t<key>ProgramArguments</key>")
            appendLine("\t<array>")
            appendLine("\t\t<string>/opt/Blaze/bin/Blaze</string>")
            appendLine("\t\t<string>--minimized</string>")
            appendLine("\t</array>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

        assertEquals("/opt/Blaze/bin/Blaze", MacOsAutoStartService.parseProgramArguments(plist))
    }

    @Test
    fun parseProgramArgumentsUnescapesAndStaysQuietOnForeignFiles() {
        val escaped = """
            <key>ProgramArguments</key>
            <array><string>/opt/A&amp;B/Blaze &lt;test&gt;</string></array>
        """.trimIndent()
        assertEquals("/opt/A&B/Blaze <test>", MacOsAutoStartService.parseProgramArguments(escaped))

        assertNull(MacOsAutoStartService.parseProgramArguments("<dict><key>RunAtLoad</key><true/></dict>"))
        assertNull(MacOsAutoStartService.parseProgramArguments(""))
        assertNull(
            MacOsAutoStartService.parseProgramArguments("<key>ProgramArguments</key><array></array>"),
            "an empty array is not a program",
        )
    }
}
