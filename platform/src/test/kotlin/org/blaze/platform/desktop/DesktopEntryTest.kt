package org.blaze.platform.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parsing every cleanup verdict is built on: which program an entry launches, which icon it pins,
 * and whether a path is a stable install or just somewhere that happens to be mounted right now.
 * A wrong answer here deletes a working registration, so the "must never fire" cases matter as much
 * as the happy ones.
 */
class DesktopEntryTest {

    private fun entry(vararg lines: String): DesktopEntry =
        DesktopEntry.parse((listOf("[Desktop Entry]") + lines).joinToString("\n"))

    @Test
    fun `keys are read trimmed and absent keys are null`() {
        val parsed = entry("Name=Blaze  ", "Terminal=false")

        assertEquals("Blaze", parsed.key("Name"))
        assertEquals("false", parsed.key("Terminal"))
        assertNull(parsed.key("Icon"))
        assertNull(parsed.startupWmClass)
    }

    @Test
    fun `a quoted launcher with field codes still names its executable`() {
        // Exactly the shape LauncherEntryInstaller writes.
        val parsed = entry("""Exec="/opt/Blaze/bin/Blaze" %F""")

        assertEquals(Paths.get("/opt/Blaze/bin/Blaze"), parsed.executable())
    }

    @Test
    fun `an unquoted launcher drops its arguments`() {
        assertEquals(Paths.get("/opt/Blaze/bin/Blaze"), entry("Exec=/opt/Blaze/bin/Blaze --startup").executable())
    }

    @Test
    fun `a PATH relative exec resolves to no path at all`() {
        // `Exec=blaze` is resolved by the session's PATH; treating it as ./blaze would let a cleanup
        // declare a perfectly working entry dead.
        assertNull(entry("Exec=blaze").executable())
        assertNull(entry("Exec=").executable())
    }

    @Test
    fun `only a path-like icon has a path`() {
        assertEquals(Paths.get("/home/u/.local/share/icons/org.blaze.svg"), entry("Icon=/home/u/.local/share/icons/org.blaze.svg").iconPath())
        assertNull(entry("Icon=org.blaze").iconPath(), "a themed icon name has no path to check")
        assertNull(entry("Name=Blaze").iconPath())
    }

    @Test
    fun `withIcon rewrites in place and keeps every other line`() {
        val original = entry(
            "Name=Blaze",
            "Exec=/opt/Blaze/bin/Blaze",
            "Icon=/gone/org.blaze.png",
            "X-KDE-Portal-Library=libfizzle",
            "StartupWMClass=org-blaze-MainKt",
        )

        val repaired = original.withIcon("/home/u/.local/share/icons/hicolor/scalable/apps/org.blaze.svg")
        val content = repaired.content()

        assertTrue(content.contains("Icon=/home/u/.local/share/icons/hicolor/scalable/apps/org.blaze.svg"))
        assertFalse(content.contains("/gone/org.blaze.png"), "the dead icon path is gone")
        assertTrue(content.contains("X-KDE-Portal-Library=libfizzle"), "a key Blaze does not know survives")
        assertTrue(content.contains("Exec=/opt/Blaze/bin/Blaze"))
        assertEquals(original.key("Name"), repaired.key("Name"))
    }

    @Test
    fun `withIcon appends when the entry has no icon line`() {
        val content = entry("Name=Blaze", "StartupWMClass=org-blaze-MainKt").withIcon("/x/org.blaze.png").content()

        assertTrue(content.contains("Icon=/x/org.blaze.png"))
        assertTrue(content.endsWith("\n"), "desktop entries are newline-terminated")
    }

    @Test
    fun `an unreadable or missing file parses as nothing`() {
        val missing: Path = Files.createTempDirectory("blaze-desktop-entry").resolve("absent.desktop")

        assertNull(DesktopEntry.read(missing))
    }

    @Test
    fun `a file on disk parses back with its keys intact`() {
        val dir = Files.createTempDirectory("blaze-desktop-entry")
        val file = dir.resolve("org.blaze.desktop")
        Files.writeString(file, "[Desktop Entry]\nType=Application\nStartupWMClass=org-blaze-MainKt\n")

        val parsed = DesktopEntry.read(file)

        assertEquals("org-blaze-MainKt", parsed?.startupWmClass)
        assertEquals("Application", parsed?.key("Type"))
    }

    @Test
    fun `transient locations are ephemeral and stable installs are not`() {
        assertTrue(DesktopEntry.isEphemeral(Paths.get("/tmp/Blaze/bin/Blaze")), "a debug/scratch tree")
        assertTrue(DesktopEntry.isEphemeral(Paths.get("/tmp/.mount_BlazeQk1z2f/Blaze")), "an AppImage FUSE mount")
        assertTrue(DesktopEntry.isEphemeral(Paths.get("/media/usb/Blaze/Blaze")), "removable media")
        assertTrue(DesktopEntry.isEphemeral(Paths.get("/mnt/dmg/Blaze.app/Contents/MacOS/Blaze")))
        assertTrue(DesktopEntry.isEphemeral(Paths.get("/Volumes/Blaze/Blaze.app/Contents/MacOS/Blaze")))

        assertFalse(DesktopEntry.isEphemeral(Paths.get("/opt/Blaze/bin/Blaze")), "a real install prefix")
        assertFalse(DesktopEntry.isEphemeral(Paths.get("/home/u/.local/share/applications/org.blaze.desktop")))
    }
}
