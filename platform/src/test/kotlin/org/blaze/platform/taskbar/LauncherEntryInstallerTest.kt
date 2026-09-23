package org.blaze.platform.taskbar

import org.blaze.platform.TEST_IDENTITY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherEntryInstallerTest {

    private fun tempDirs(): Pair<Path, Path> {
        // <root>/org.blaze/{bin,lib/app} mimics a jpackage install layout.
        val root = Files.createTempDirectory("blaze-launcher-entry")
        val appDir = root.resolve("org.blaze")
        Files.createDirectories(appDir.resolve("bin"))
        Files.createDirectories(appDir.resolve("lib").resolve("app"))
        Files.writeString(appDir.resolve("bin").resolve("org.blaze"), "#!/bin/sh")
        return root to appDir
    }

    @Test
    fun `packaged launch installs a desktop entry with window class matching`() {
        val (root, appDir) = tempDirs()
        val desktopDir = root.resolve("applications")
        var refreshes = 0

        LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = appDir.resolve("bin").resolve("org.blaze").toString(),
            kServiceCacheRefresher = { refreshes++ },
        ).ensureInstalled()

        val file = desktopDir.resolve(TEST_IDENTITY.desktopFileName)
        assertTrue(Files.exists(file), "the desktop entry should be installed")

        val content = Files.readString(file)
        assertTrue(content.contains("StartupWMClass=${TEST_IDENTITY.windowManagerClass}"))
        assertTrue(
            content.contains("Exec=\"${appDir.resolve("bin").resolve("org.blaze")}\" %F"),
            "the quoted launcher must accept file arguments for OS-initiated opens",
        )
        assertTrue(
            content.contains("MimeType=application/x-bittorrent;x-scheme-handler/magnet;"),
            "torrent + magnet associations make xdg resolve opens to this entry",
        )
        assertTrue(content.contains("Name=${TEST_IDENTITY.appName}"))
        assertFalse(content.contains("Icon="), "no icon line when the icon file is missing")
        assertEquals(1, refreshes, "the KService cache should be refreshed once")
    }

    @Test
    fun `icon line appears when the packaged icon exists`() {
        val (root, appDir) = tempDirs()
        Files.writeString(appDir.resolve("lib").resolve("org.blaze.png"), "not-a-png")
        val desktopDir = root.resolve("applications")

        LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = appDir.resolve("bin").resolve("org.blaze").toString(),
            kServiceCacheRefresher = {},
        ).ensureInstalled()

        val content = Files.readString(desktopDir.resolve(TEST_IDENTITY.desktopFileName))
        assertTrue(content.contains("Icon=${appDir.resolve("lib").resolve("org.blaze.png")}"))
    }

    @Test
    fun `an up-to-date entry is not rewritten nor re-refreshed`() {
        val (root, appDir) = tempDirs()
        val desktopDir = root.resolve("applications")
        val command = appDir.resolve("bin").resolve("org.blaze").toString()
        var refreshes = 0

        val installer = LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = command,
            kServiceCacheRefresher = { refreshes++ },
        )
        installer.ensureInstalled()
        installer.ensureInstalled()

        assertEquals(1, refreshes)
    }

    @Test
    fun `a non-packaged launch (plain java binary) installs nothing`() {
        val (root, _) = tempDirs()
        // A bin+lib pair that is not a jpackage layout (no lib/app marker).
        val fakeUsr = root.resolve("usr")
        Files.createDirectories(fakeUsr.resolve("bin"))
        Files.createDirectories(fakeUsr.resolve("lib"))
        Files.writeString(fakeUsr.resolve("bin").resolve("java"), "#!/bin/sh")
        val desktopDir = root.resolve("applications")
        var refreshes = 0

        LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = fakeUsr.resolve("bin").resolve("java").toString(),
            kServiceCacheRefresher = { refreshes++ },
        ).ensureInstalled()

        assertFalse(Files.exists(desktopDir.resolve(TEST_IDENTITY.desktopFileName)))
        assertEquals(0, refreshes)
    }

    @Test
    fun `packaged launch pins the installed icon path and copies the png`() {
        val (root, appDir) = tempDirs()
        val desktopDir = root.resolve("applications")
        val iconDir = root.resolve("icons")

        LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = appDir.resolve("bin").resolve("org.blaze").toString(),
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = {},
        ).ensureInstalled()

        val installed = iconDir.resolve("${TEST_IDENTITY.appId}.png")
        val content = Files.readString(desktopDir.resolve(TEST_IDENTITY.desktopFileName))
        assertTrue(
            content.contains("Icon=${installed.toAbsolutePath()}"),
            "icon referenced by the installed absolute path",
        )
        assertTrue(Files.exists(installed), "the bundled png should be copied to the stable location")
    }

    @Test
    fun `dev launch repairs the icon of an entry whose launcher still exists`() {
        val (root, appDir) = tempDirs()
        val desktopDir = root.resolve("applications")
        Files.createDirectories(desktopDir)
        val target = desktopDir.resolve(TEST_IDENTITY.desktopFileName)
        Files.writeString(
            target,
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Type=Application")
                appendLine("Name=Blaze")
                appendLine("Exec=${appDir.resolve("bin").resolve("org.blaze")}")
                appendLine("Icon=/opt/org.blaze/lib/org.blaze.png")
                appendLine("StartupWMClass=${TEST_IDENTITY.windowManagerClass}")
            },
        )
        val iconDir = root.resolve("icons")

        val removed = LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = {},
        ).ensureInstalled()

        val content = Files.readString(target)
        assertTrue(
            content.contains("Icon=${iconDir.resolve("${TEST_IDENTITY.appId}.png").toAbsolutePath()}"),
            "the dead icon path is repointed to the installed one",
        )
        assertFalse(content.contains("/opt/org.blaze/lib/org.blaze.png"), "the stale path is gone")
        assertTrue(content.contains("StartupWMClass=${TEST_IDENTITY.windowManagerClass}"))
        assertTrue(Files.exists(iconDir.resolve("${TEST_IDENTITY.appId}.png")), "the repair needs the icon published")
        assertTrue(removed.isEmpty(), "a working entry is never deleted")
    }

    @Test
    fun `an entry whose launcher was uninstalled is pruned along with its orphaned icon`() {
        val (root, _) = tempDirs()
        val desktopDir = root.resolve("applications")
        Files.createDirectories(desktopDir)
        val target = desktopDir.resolve(TEST_IDENTITY.desktopFileName)
        Files.writeString(
            target,
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Exec=/opt/org.blaze/bin/org.blaze")
                appendLine("Icon=/opt/org.blaze/lib/org.blaze.png")
                appendLine("MimeType=application/x-bittorrent;x-scheme-handler/magnet;")
                appendLine("StartupWMClass=${TEST_IDENTITY.windowManagerClass}")
            },
        )
        val iconDir = root.resolve("icons")
        val published = iconDir.resolve("${TEST_IDENTITY.appId}.png")
        Files.createDirectories(iconDir)
        Files.write(published, byteArrayOf(1, 2, 3))
        var refreshes = 0

        val removed = LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = { refreshes++ },
        ).ensureInstalled()

        assertFalse(Files.exists(target), "a leftover entry claims magnet: and then fails on click")
        assertFalse(Files.exists(published), "nothing references the published icon any more")
        assertEquals(listOf(target, published), removed)
        assertEquals(1, refreshes, "the desktop cache is nudged once after the removal")
    }

    @Test
    fun `an appimage entry on an ephemeral launcher path is kept`() {
        val (root, _) = tempDirs()
        val desktopDir = root.resolve("applications")
        Files.createDirectories(desktopDir)
        val target = desktopDir.resolve(TEST_IDENTITY.desktopFileName)
        // A jpackage AppImage launches from a libfuse mount that is *supposed* to be gone between runs.
        Files.writeString(
            target,
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Exec=/tmp/.mount_BlazeQk1z2f/Blaze")
                appendLine("StartupWMClass=${TEST_IDENTITY.windowManagerClass}")
            },
        )

        val removed = LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = root.resolve("icons"),
            iconBytes = { null },
            kServiceCacheRefresher = {},
        ).ensureInstalled()

        assertTrue(removed.isEmpty())
        assertTrue(Files.exists(target), "a transient mount path is not evidence of an uninstall")
    }

    @Test
    fun `dev launch installs neither a menu entry nor an orphan icon`() {
        val (root, _) = tempDirs()
        val desktopDir = root.resolve("applications")
        val iconDir = root.resolve("icons")

        LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = {},
        ).ensureInstalled()

        assertFalse(Files.exists(iconDir.resolve("${TEST_IDENTITY.appId}.png")), "a debug run leaves no orphan icon")
        assertFalse(
            Files.exists(desktopDir.resolve(TEST_IDENTITY.desktopFileName)),
            "a debug run never creates a fresh menu entry, even with a themed icon available",
        )
    }

    @Test
    fun `uninstall removes our entry and the icon it owned`() {
        val (root, appDir) = tempDirs()
        val desktopDir = root.resolve("applications")
        val iconDir = root.resolve("icons")
        val installer = LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = appDir.resolve("bin").resolve("org.blaze").toString(),
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = {},
        )
        installer.ensureInstalled()
        val entry = desktopDir.resolve(TEST_IDENTITY.desktopFileName)
        val icon = iconDir.resolve("${TEST_IDENTITY.appId}.png")
        assertTrue(Files.exists(entry) && Files.exists(icon), "precondition: the registration exists")

        val removed = installer.uninstall()

        assertFalse(Files.exists(entry))
        assertFalse(Files.exists(icon), "an icon no entry references is desktop clutter")
        assertEquals(listOf(entry, icon), removed)
    }

    @Test
    fun `uninstall leaves a foreign entry and an icon another entry still uses`() {
        val (root, _) = tempDirs()
        val desktopDir = root.resolve("applications")
        Files.createDirectories(desktopDir)
        // Somebody else's entry (different window class) that happens to pin our published icon.
        val foreign = desktopDir.resolve("other.app.desktop")
        val iconDir = root.resolve("icons")
        Files.createDirectories(iconDir)
        val icon = iconDir.resolve("${TEST_IDENTITY.appId}.png")
        Files.write(icon, byteArrayOf(1, 2, 3))
        Files.writeString(
            foreign,
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Exec=other-app")
                appendLine("Icon=${icon.toAbsolutePath()}")
                appendLine("StartupWMClass=OtherApp")
            },
        )
        val ours = desktopDir.resolve(TEST_IDENTITY.desktopFileName)
        Files.writeString(
            ours,
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Exec=${root.resolve("gone").resolve("org.blaze")}")
                appendLine("StartupWMClass=${TEST_IDENTITY.windowManagerClass}")
            },
        )

        val installer = LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = {},
        )
        installer.uninstall()

        assertFalse(Files.exists(ours), "our own entry went")
        assertTrue(Files.exists(foreign), "an entry Blaze did not write is never Blaze's to delete")
        assertTrue(Files.exists(icon), "the icon is still referenced, so it stays")
    }

    @Test
    fun `uninstall collects an orphaned icon even in a format the current build does not ship`() {
        // An older release published a PNG; this one ships an SVG. The icon directory holds nothing but
        // this app's own files, so `<appId>.<ext>` is unambiguous residue of an earlier build.
        val (root, _) = tempDirs()
        val desktopDir = root.resolve("applications")
        val iconDir = root.resolve("icons")
        Files.createDirectories(desktopDir) // no entry installed: nothing can be referencing the icon
        Files.createDirectories(iconDir)
        val previousPng = iconDir.resolve("${TEST_IDENTITY.appId}.png")
        val unrelated = iconDir.resolve("readme.md") // a name that is not ours at all
        Files.write(previousPng, byteArrayOf(1, 2, 3))
        Files.write(unrelated, byteArrayOf(4))

        val removed = LauncherEntryInstaller(
            identity = TEST_IDENTITY.copy(iconResource = "/app-icon.svg"),
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = iconDir,
            iconBytes = { null },
            kServiceCacheRefresher = {},
        ).uninstall()

        assertTrue(previousPng in removed, "the superseded icon went: $removed")
        assertFalse(Files.exists(previousPng))
        assertTrue(Files.exists(unrelated), "only this app's own file names are ever in scope")
    }

    @Test
    fun `an icon another entry names through the theme is kept`() {
        // jpackage's generated entry refers to the icon by theme name, not path - which the icon theme
        // resolves to exactly the file in question, so it is as binding as an absolute path.
        val (root, _) = tempDirs()
        val desktopDir = root.resolve("applications")
        Files.createDirectories(desktopDir)
        val iconDir = root.resolve("icons")
        Files.createDirectories(iconDir)
        val icon = iconDir.resolve("${TEST_IDENTITY.appId}.png")
        Files.write(icon, byteArrayOf(1, 2, 3))
        Files.writeString(
            desktopDir.resolve("other.desktop"),
            "[Desktop Entry]\nType=Application\nName=Other\nExec=other\nIcon=${TEST_IDENTITY.appId}\n",
        )

        LauncherEntryInstaller(
            identity = TEST_IDENTITY,
            desktopDir = desktopDir,
            launcherCommand = null,
            iconDir = iconDir,
            iconBytes = { null },
            kServiceCacheRefresher = {},
        ).uninstall()

        assertTrue(Files.exists(icon), "an entry resolves it through the icon theme")
    }
}
