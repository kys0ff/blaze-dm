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
        assertTrue(content.contains("Exec=${appDir.resolve("bin").resolve("org.blaze")}"))
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
}
