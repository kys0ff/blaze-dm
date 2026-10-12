package org.blaze.platform.cleanup

import org.blaze.download.api.ServicePaths
import org.blaze.platform.api.OsType
import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.autostart.AutoStartController
import org.blaze.platform.autostart.LinuxAutoStartService
import org.blaze.platform.taskbar.LauncherEntryInstaller
import org.blaze.service.instance.ServiceLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Blaze's own cleanup pass, driven against temp directories so the real `~/.local/share`,
 * `~/.config/autostart` and `~/.blaze` are never at stake.
 *
 * What is being pinned down is the *blast radius*, not the mechanics: a startup prune must survive a
 * healthy install untouched, an end-of-session sweep may never delete a running process's files or
 * another instance's secret, and no pass - not even `--cleanup` - may lay a hand on downloads, settings
 * or logs.
 */
class OsIntegrationCleanupTest {

    private val identity = PlatformIdentity(
        appId = "org.blaze",
        appName = "Blaze",
        description = "Blaze download manager",
        genericName = "Download Manager",
        mainClass = "org.blaze.MainKt",
        executableName = "blaze",
    )

    private val roots = mutableListOf<Path>()

    private fun dir(name: String): Path = Files.createTempDirectory("blaze-cleanup-$name").also { roots.add(it) }

    private val desktopDir = dir("applications")
    private val iconDir = dir("icons")
    private val autostartDir = dir("autostart")
    private val storageDir = dir("storage")

    @AfterTest
    fun tearDown() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    /** A cleanup wired to nothing but temp dirs; the Linux branch, whatever the host is. */
    private fun cleanup(
        launchCommand: String? = "/opt/Blaze/bin/Blaze",
        staleTempFileAge: Long = OsIntegrationCleanup.DEFAULT_STALE_TEMP_AGE_MILLIS,
    ): OsIntegrationCleanup = OsIntegrationCleanup(
        identity = identity,
        storageDir = storageDir,
        launcherEntryInstaller = LauncherEntryInstaller(
            identity = identity,
            desktopDir = desktopDir,
            launcherCommand = launchCommand,
            iconDir = iconDir,
            iconBytes = { byteArrayOf(1, 2, 3) },
            kServiceCacheRefresher = {},
        ),
        autoStartController = AutoStartController(
            LinuxAutoStartService(identity, autostartDir) { launchCommand ?: identity.executableName },
        ),
        osType = OsType.LINUX,
        staleTempFileAge = staleTempFileAge,
    )

    private fun writeAutostartEntry(exec: String): Path {
        Files.createDirectories(autostartDir)
        return autostartDir.resolve(identity.desktopFileName).also {
            Files.writeString(it, "[Desktop Entry]\nType=Application\nName=Blaze\nExec=$exec\n")
        }
    }

    /** A data directory left exactly as a killed JVM leaves it. */
    private fun populateStorage(): Path {
        Files.writeString(ServicePaths.instanceSocketFileFor(storageDir), "stale")
        Files.writeString(ServicePaths.instanceTokenFileFor(storageDir), "secret")
        Files.writeString(ServicePaths.endpointFileFor(storageDir), """{"host":"127.0.0.1","port":1}""")
        Files.writeString(ServicePaths.tokenFileFor(storageDir), "api-secret")
        Files.writeString(storageDir.resolve("downloads.json"), "[]")
        Files.writeString(storageDir.resolve("settings.json"), "{}")
        Files.createDirectories(storageDir.resolve("logs"))
        Files.writeString(storageDir.resolve("logs").resolve("blaze.log"), "hello")
        return storageDir
    }

    @Test
    fun `startup prune drops the login item a debug session left behind`() {
        val entry = writeAutostartEntry("/home/dev/.jdks/jbr-21/bin/java")

        val report = cleanup().pruneStaleRegistrations()

        assertFalse(Files.exists(entry), "a login item that can only fail at every sign-in must go")
        assertTrue(
            report.notes.any { "run-at-startup" in it },
            "the user is told what was removed: ${report.notes}",
        )
    }

    @Test
    fun `startup prune leaves a registration it cannot prove dead alone`() {
        val pathRelative = writeAutostartEntry("blaze")

        val first = cleanup().pruneStaleRegistrations()
        assertTrue(Files.exists(pathRelative), "a PATH-relative launcher is resolved by the session, not by us")
        assertTrue(first.notes.isEmpty(), "nothing was judged stale: ${first.notes}")

        // One autostart file per app, so the second shape is written only once the first has survived.
        val appImage = writeAutostartEntry("/tmp/.mount_BlazeQk1z2f/Blaze")

        val second = cleanup().pruneStaleRegistrations()
        assertTrue(Files.exists(appImage), "an AppImage mount is gone between runs by design")
        assertTrue(second.notes.isEmpty(), "nothing was judged stale: ${second.notes}")
    }

    @Test
    fun `the session sweep keeps secrets and user data`() {
        populateStorage()

        val report = cleanup().sweepSessionFiles(includeSecrets = false)

        assertTrue(ServicePaths.instanceSocketFileFor(storageDir) in report.removed)
        assertTrue(ServicePaths.endpointFileFor(storageDir) in report.removed, "a dead endpoint is a CLI lie")
        assertTrue(Files.exists(ServicePaths.instanceTokenFileFor(storageDir)), "IPC secret survives")
        assertTrue(Files.exists(ServicePaths.tokenFileFor(storageDir)), "API secret survives")
        assertTrue(Files.exists(storageDir.resolve("downloads.json")), "user data is never in scope")
        assertTrue(Files.exists(storageDir.resolve("settings.json")))
        assertTrue(Files.exists(storageDir.resolve("logs").resolve("blaze.log")))
    }

    @Test
    fun `a live process's files are never swept`() {
        populateStorage()
        val apiLock = ServiceLock(ServicePaths.lockFileFor(storageDir))
        assertTrue(apiLock.acquire(), "this test plays the running service")
        val instanceLock = org.blaze.osintegration.singleinstance.InstanceLock(
            ServicePaths.instanceLockFileFor(storageDir),
        )
        assertTrue(instanceLock.acquire(), "and the running GUI")
        try {
            val report = cleanup().sweepSessionFiles(includeSecrets = true)

            assertFalse(ServicePaths.endpointFileFor(storageDir) in report.removed)
            assertFalse(ServicePaths.instanceSocketFileFor(storageDir) in report.removed)
            assertTrue(Files.exists(ServicePaths.endpointFileFor(storageDir)), "a live API's descriptor stays")
            assertTrue(Files.exists(ServicePaths.instanceSocketFileFor(storageDir)), "a live owner's socket stays")
            // A same-JVM re-lock surfaces as an OverlappingFileLockException, not as a refusal the
            // probe could formerly misread as "free" - which used to hand this process's own
            // endpoint file to the sweep and release the live service's lock out from under it.
            assertEquals(emptyList(), report.removed, "nothing this JVM owns is debris")
        } finally {
            instanceLock.close()
            apiLock.close()
        }
    }

    @Test
    fun `an abandoned partial write is collected while a write in flight survives`() {
        populateStorage()
        val abandoned = storageDir.resolve("downloads.json.tmp")
        val inFlight = storageDir.resolve("settings.json.tmp")
        Files.writeString(abandoned, "{ half")
        Files.writeString(inFlight, "{ half")
        Files.setLastModifiedTime(abandoned, FileTime.fromMillis(System.currentTimeMillis() - 60_000))

        val report = cleanup().sweepSessionFiles(includeSecrets = false)

        assertTrue(abandoned in report.removed, "a week-old temp file is debris: ${report.removed}")
        assertFalse(Files.exists(abandoned))
        assertTrue(Files.exists(inFlight), "a fresh temp file may belong to a write happening right now")
    }

    @Test
    fun `the uninstall pass removes every registration the app published`() {
        val installerEntry = desktopDir.resolve(identity.desktopFileName)
        Files.createDirectories(desktopDir)
        Files.writeString(
            installerEntry,
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Type=Application")
                appendLine("Name=Blaze")
                appendLine("Exec=/opt/org.blaze/bin/org.blaze")
                appendLine("MimeType=application/x-bittorrent;x-scheme-handler/magnet;")
                appendLine("StartupWMClass=${identity.windowManagerClass}")
            },
        )
        val icon = iconDir.resolve("${identity.appId}.png")
        Files.createDirectories(iconDir)
        Files.write(icon, byteArrayOf(1, 2, 3))
        val autostart = writeAutostartEntry("/opt/Blaze/bin/Blaze")
        populateStorage()

        val report = cleanup().uninstallRegistrations()

        assertFalse(Files.exists(installerEntry), "the menu entry that claims magnet: is gone")
        assertFalse(Files.exists(icon), "and the icon only it referenced went with it")
        assertFalse(Files.exists(autostart), "no login item outlives the app")
        assertTrue(ServicePaths.instanceTokenFileFor(storageDir) in report.removed, "the uninstall sweep takes secrets")
        assertTrue(ServicePaths.tokenFileFor(storageDir) in report.removed)
        assertTrue(
            report.notes.any { "removed the run-at-startup registration" in it },
            "the report accounts for what it took back, not only the files it can name: ${report.notes}",
        )
        assertTrue(Files.exists(storageDir.resolve("downloads.json")), "removing a program is not permission to erase it")
        assertTrue(Files.exists(storageDir.resolve("settings.json")))
    }

    @Test
    fun `a second cleanup pass on a clean system has nothing to say`() {
        populateStorage()
        writeAutostartEntry("/opt/Blaze/bin/Blaze")

        cleanup().uninstallRegistrations()
        val second = cleanup().uninstallRegistrations()

        // Probing ownership used to create the lock files it inspected, so every repeat run reported
        // deleting a zero-byte file it had just invented - which reads as "still not clean" forever.
        assertFalse(second.didAnything, "the app is gone; the report should say so: ${second.describe()}")
        assertEquals("nothing to clean up\n", second.describe())
    }

    @Test
    fun `a report says what it did and admits when it did nothing`() {
        val report = OsIntegrationCleanup.Report(
            removed = listOf(Path.of("/home/u/.config/autostart/org.blaze.desktop")),
            notes = listOf("dropped a run-at-startup registration"),
        )

        val text = report.describe()

        assertTrue("removed  /home/u/.config/autostart/org.blaze.desktop" in text, text)
        assertTrue("note     dropped a run-at-startup registration" in text, text)
        assertTrue(report.didAnything)
        assertEquals("nothing to clean up\n", OsIntegrationCleanup.Report().describe())
        assertFalse(OsIntegrationCleanup.Report().didAnything)
    }

    @Test
    fun `merging passes keeps both the files and the explanations`() {
        val first = OsIntegrationCleanup.Report(removed = listOf(Path.of("/a")), notes = listOf("one"))
        val second = OsIntegrationCleanup.Report(notes = listOf("two"))

        val merged = first.merge(second)

        assertEquals(listOf(Path.of("/a")), merged.removed)
        assertEquals(listOf("one", "two"), merged.notes)
    }
}
